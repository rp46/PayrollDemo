package com.payroll.demo.service;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.Employee;
import com.payroll.demo.domain.Payslip;
import com.payroll.demo.domain.PayslipLine;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.PayrollDtos.ComponentTotalView;
import com.payroll.demo.dto.PayrollDtos.PayDetailsView;
import com.payroll.demo.dto.PayrollDtos.PaySummaryView;
import com.payroll.demo.dto.PayrollDtos.PayslipLineView;
import com.payroll.demo.dto.PayrollDtos.PayslipView;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.repository.EmployeeRepository;
import com.payroll.demo.repository.PayPeriodRepository;
import com.payroll.demo.repository.PayslipRepository;
import com.payroll.demo.util.MoneyUtils;
import com.payroll.demo.util.ServiceGuard;
import com.payroll.demo.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads payslips and derives an employee's pay details.
 *
 * <p>Every public method validates its arguments first, then runs through
 * {@link ServiceGuard}, so each one logs its start and finish and returns only exceptions
 * the web layer can map to a status. Nothing raw reaches the controller.
 *
 * <p>The aggregates in {@link #payDetailsFor} are computed in Java rather than by a SQL
 * {@code GROUP BY}. The scope is one employee's payslips — tens of rows, hundreds at
 * worst — and the line items have to be loaded anyway for the component breakdown, so a
 * second aggregate query would read the same rows twice to save nothing. It also means the
 * arithmetic goes through {@link MoneyUtils}, so the totals obey the same scale and
 * comparison rules as every other amount in the system.
 */
@Service
@Transactional(readOnly = true)
public class PayslipQueryService {

    private static final Logger log = LoggerFactory.getLogger(PayslipQueryService.class);

    private static final int MAX_EMPLOYEE_CODE = 20;

    private final EmployeeRepository employees;
    private final PayPeriodRepository payPeriods;
    private final PayslipRepository payslips;

    public PayslipQueryService(EmployeeRepository employees,
                               PayPeriodRepository payPeriods,
                               PayslipRepository payslips) {
        this.employees = employees;
        this.payPeriods = payPeriods;
        this.payslips = payslips;
    }

    /**
     * One employee's payslips, newest period first.
     *
     * @param from   include payslips whose period ends on or after this date; null for no bound
     * @param to     include payslips whose period starts on or before this date; null for no bound
     * @param status only this status; null for all
     * @throws ApiException 404 if the code matches no employee, so an unknown employee is
     *                       distinguishable from one who simply has no payslips
     */
    public List<PayslipView> listPayslipsFor(String employeeCode,
                                             LocalDate from,
                                             LocalDate to,
                                             PayslipStatus status) {
        String code = validatedCode(employeeCode);
        Validate.requireValidRange(from, to);

        String operation = "list payslips for " + code
                + " (from=" + from + ", to=" + to + ", status=" + status + ")";

        return ServiceGuard.call(log, operation, () -> {
            requireEmployee(code);
            List<PayslipView> found = payslips.findForEmployee(code, from, to, status).stream()
                    .map(PayslipQueryService::toView)
                    .toList();
            log.info("Employee {} has {} payslip(s) in scope", code, found.size());
            return found;
        });
    }

    /** The most recent payslip for an employee, by period. */
    public PayslipView latestPayslipFor(String employeeCode) {
        String code = validatedCode(employeeCode);

        return ServiceGuard.call(log, "find latest payslip for " + code, () -> {
            requireEmployee(code);
            return payslips.findForEmployee(code, null, null, null).stream()
                    .findFirst()
                    .map(PayslipQueryService::toView)
                    .orElseThrow(() -> {
                        log.warn("Employee {} exists but has no payslips yet", code);
                        return ApiException.notFound("No payslips exist for employee " + code);
                    });
        });
    }

    /** A single payslip by its id. */
    public PayslipView findPayslip(Long id) {
        Long payslipId = Validate.requireId(id, "payslip id");

        return ServiceGuard.call(log, "find payslip " + payslipId,
                () -> payslips.findWithDetailById(payslipId)
                        .map(PayslipQueryService::toView)
                        .orElseThrow(() -> {
                            log.warn("No payslip stored with id {}", payslipId);
                            return ApiException.notFound("No payslip with id " + payslipId);
                        }));
    }

    /** Every payslip in one pay period: the payroll register for that run. */
    public List<PayslipView> listPayslipsForPeriod(Long payPeriodId) {
        Long periodId = Validate.requireId(payPeriodId, "pay period id");

        return ServiceGuard.call(log, "list payslips for pay period " + periodId, () -> {
            if (!payPeriods.existsById(periodId)) {
                log.warn("No pay period stored with id {}", periodId);
                throw ApiException.notFound("No pay period with id " + periodId);
            }
            List<PayslipView> register =
                    payslips.findByPayPeriodIdOrderByEmployeeEmployeeCodeAsc(periodId).stream()
                            .map(PayslipQueryService::toView)
                            .toList();
            log.info("Pay period {} covers {} payslip(s)", periodId, register.size());
            return register;
        });
    }

    /**
     * An employee's pay details: standing compensation plus what the payslips in scope add up to.
     *
     * <p>The filters are the same as {@link #listPayslipsFor}, and are echoed back on the
     * response so a total cannot be read without knowing what it covers.
     */
    public PayDetailsView payDetailsFor(String employeeCode,
                                        LocalDate from,
                                        LocalDate to,
                                        PayslipStatus status) {
        String code = validatedCode(employeeCode);
        Validate.requireValidRange(from, to);

        String operation = "build pay details for " + code
                + " (from=" + from + ", to=" + to + ", status=" + status + ")";

        return ServiceGuard.call(log, operation, () -> buildPayDetails(code, from, to, status));
    }

    private PayDetailsView buildPayDetails(String code, LocalDate from, LocalDate to, PayslipStatus status) {
        Employee employee = requireEmployee(code);
        List<Payslip> inScope = payslips.findForEmployee(code, from, to, status);

        if (inScope.isEmpty()) {
            log.info("No payslips in scope for {}; returning standing compensation only", code);
        } else {
            log.info("Summarising {} payslip(s) for {}", inScope.size(), code);
        }

        return new PayDetailsView(
                employee.getEmployeeCode(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getStatus(),
                employee.getDepartment() == null ? null : employee.getDepartment().getCode(),
                employee.getHireDate(),
                employee.getTerminationDate(),
                employee.getBaseSalary(),
                employee.getLastSyncedAt(),
                from,
                to,
                status,
                summarise(inScope),
                componentTotals(inScope),
                // findForEmployee orders newest first, so the head is the latest.
                inScope.isEmpty() ? null : toView(inScope.getFirst()));
    }

    /** Validated before the guard, so a blank code is refused without logging a start. */
    private static String validatedCode(String employeeCode) {
        String code = Validate.requireText(employeeCode, "employeeCode");
        return Validate.optionalText(code, "employeeCode", MAX_EMPLOYEE_CODE);
    }

    private Employee requireEmployee(String employeeCode) {
        return employees.findByEmployeeCode(employeeCode)
                .orElseThrow(() -> {
                    log.warn("No employee stored with code {}", employeeCode);
                    return ApiException.notFound("No employee with code " + employeeCode);
                });
    }

    private static PaySummaryView summarise(List<Payslip> inScope) {
        if (inScope.isEmpty()) {
            return new PaySummaryView(0, null, null,
                    MoneyUtils.ZERO, MoneyUtils.ZERO, MoneyUtils.ZERO, null);
        }

        BigDecimal totalGross = MoneyUtils.sum(inScope.stream().map(Payslip::getGrossPay).toList());
        BigDecimal totalDeductions = MoneyUtils.sum(inScope.stream().map(Payslip::getTotalDeductions).toList());
        BigDecimal totalNet = MoneyUtils.sum(inScope.stream().map(Payslip::getNetPay).toList());

        LocalDate first = inScope.stream()
                .map(p -> p.getPayPeriod().getPeriodStart())
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDate last = inScope.stream()
                .map(p -> p.getPayPeriod().getPeriodEnd())
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new PaySummaryView(
                inScope.size(), first, last,
                totalGross, totalDeductions, totalNet,
                MoneyUtils.average(totalNet, inScope.size()));
    }

    /**
     * Sums every line item by component code.
     *
     * <p>Earnings are listed before deductions, then alphabetically, so the breakdown reads
     * the way a payslip does rather than in whatever order the rows came back.
     */
    private static List<ComponentTotalView> componentTotals(List<Payslip> inScope) {
        record Key(String code, ComponentType type) {
        }
        Map<Key, List<BigDecimal>> amounts = new LinkedHashMap<>();

        for (Payslip payslip : inScope) {
            for (PayslipLine line : payslip.getLines()) {
                amounts.computeIfAbsent(new Key(line.getComponentCode(), line.getComponentType()),
                        k -> new ArrayList<>()).add(line.getAmount());
            }
        }

        return amounts.entrySet().stream()
                .map(e -> new ComponentTotalView(
                        e.getKey().code(),
                        e.getKey().type(),
                        MoneyUtils.sum(e.getValue()),
                        e.getValue().size()))
                .sorted(Comparator.comparing(ComponentTotalView::componentType)
                        .thenComparing(ComponentTotalView::componentCode))
                .toList();
    }

    private static PayslipView toView(Payslip p) {
        List<PayslipLineView> lines = p.getLines().stream()
                .map(l -> new PayslipLineView(
                        l.getComponentCode(), l.getComponentType(), l.getDescription(), l.getAmount()))
                .sorted(Comparator.comparing(PayslipLineView::componentType)
                        .thenComparing(PayslipLineView::componentCode))
                .toList();

        return new PayslipView(
                p.getId(),
                p.getEmployee().getEmployeeCode(),
                p.getPayPeriod().getPeriodStart(),
                p.getPayPeriod().getPeriodEnd(),
                p.getPayPeriod().getPayDate(),
                p.getGrossPay(),
                p.getTotalDeductions(),
                p.getNetPay(),
                p.getStatus(),
                lines);
    }
}
