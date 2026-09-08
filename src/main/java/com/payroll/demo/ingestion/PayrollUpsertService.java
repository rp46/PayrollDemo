package com.payroll.demo.ingestion;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.Department;
import com.payroll.demo.domain.Employee;
import com.payroll.demo.domain.PayPeriod;
import com.payroll.demo.domain.Payslip;
import com.payroll.demo.domain.PayslipLine;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslip;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.hrms.HrmsDtos.HrmsWorker;
import com.payroll.demo.repository.DepartmentRepository;
import com.payroll.demo.repository.EmployeeRepository;
import com.payroll.demo.repository.PayPeriodRepository;
import com.payroll.demo.repository.PayslipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Writes one HRMS record at a time into the payroll schema.
 *
 * <p>Each public method runs in its own transaction ({@code REQUIRES_NEW}), which is the
 * point of this class existing separately from {@link PayrollIngestionService}: a record
 * that violates a constraint rolls back only itself, and the remaining records in the page
 * still land. A single transaction around the whole feed would throw away a good night of
 * data because of one bad row.
 *
 * <p>Writes are idempotent — keyed on {@code employee_code} for workers and on
 * {@code (employee, pay_period)} for payslips — so re-running a failed sync is safe.
 */
@Service
public class PayrollUpsertService {

    /** Whether an upsert inserted a new row or refreshed an existing one. */
    public enum UpsertOutcome {
        CREATED, UPDATED
    }

    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final PayPeriodRepository payPeriods;
    private final PayslipRepository payslips;
    private final HrmsRecordMapper mapper;

    public PayrollUpsertService(DepartmentRepository departments,
                                EmployeeRepository employees,
                                PayPeriodRepository payPeriods,
                                PayslipRepository payslips,
                                HrmsRecordMapper mapper) {
        this.departments = departments;
        this.employees = employees;
        this.payPeriods = payPeriods;
        this.payslips = payslips;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertOutcome upsertWorker(HrmsWorker worker) {
        mapper.validateWorker(worker);

        String code = worker.workerId().strip();
        Optional<Employee> existing = employees.findByEmployeeCode(code);
        Employee employee = existing.orElseGet(Employee::new);
        UpsertOutcome outcome = existing.isPresent() ? UpsertOutcome.UPDATED : UpsertOutcome.CREATED;

        employee.setEmployeeCode(code);
        employee.setFirstName(worker.firstName().strip());
        employee.setLastName(worker.lastName().strip());
        employee.setEmail(worker.email().strip());
        employee.setHireDate(worker.hireDate());
        employee.setTerminationDate(worker.terminationDate());
        employee.setStatus(mapper.toEmployeeStatus(code, worker.employmentStatus()));
        employee.setBaseSalary(worker.baseSalary());
        employee.setDepartment(resolveDepartment(worker));
        employee.setLastSyncedAt(OffsetDateTime.now());

        employees.save(employee);
        return outcome;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertOutcome upsertPayslip(HrmsPayslip incoming) {
        if (incoming == null) {
            throw new IngestionRecordException(IngestionRecordType.PAYSLIP, null, "null payslip record in page");
        }

        // Look the period up first: whether it exists decides whether payDate is mandatory.
        Optional<PayPeriod> existingPeriod = (incoming.periodStart() == null || incoming.periodEnd() == null)
                ? Optional.empty()
                : payPeriods.findByPeriodStartAndPeriodEnd(incoming.periodStart(), incoming.periodEnd());

        mapper.validatePayslip(incoming, existingPeriod.isEmpty());

        String workerId = incoming.workerId().strip();
        Employee employee = employees.findByEmployeeCode(workerId)
                .orElseThrow(() -> new IngestionRecordException(IngestionRecordType.PAYSLIP, incoming.payslipId(),
                        "no employee with code " + workerId + "; sync workers before payslips"));

        PayPeriod period = existingPeriod.orElseGet(() -> createPeriod(incoming));

        Optional<Payslip> existing = payslips.findByEmployeeIdAndPayPeriodId(employee.getId(), period.getId());
        Payslip payslip = existing.orElseGet(Payslip::new);
        UpsertOutcome outcome = existing.isPresent() ? UpsertOutcome.UPDATED : UpsertOutcome.CREATED;

        payslip.setEmployee(employee);
        payslip.setPayPeriod(period);
        payslip.setGrossPay(incoming.grossPay());
        payslip.setTotalDeductions(incoming.totalDeductions());
        payslip.setNetPay(incoming.netPay());
        payslip.setStatus(mapper.toPayslipStatus(incoming.payslipId(), incoming.status()));
        payslip.setLastSyncedAt(OffsetDateTime.now());

        // The HRMS is authoritative for the breakdown, so replace rather than merge:
        // merging would strand a line the upstream has since removed.
        payslip.getLines().clear();
        for (HrmsPayslipLine line : incoming.safeLines()) {
            payslip.addLine(toLine(incoming.payslipId(), line));
        }

        payslips.save(payslip);
        return outcome;
    }

    private PayslipLine toLine(String payslipId, HrmsPayslipLine source) {
        ComponentType type = mapper.toComponentType(payslipId, source.componentType(), source.componentCode());
        PayslipLine line = new PayslipLine();
        line.setComponentCode(source.componentCode().strip());
        line.setComponentType(type);
        line.setDescription(source.description() == null ? null : source.description().strip());
        line.setAmount(source.amount());
        return line;
    }

    private PayPeriod createPeriod(HrmsPayslip incoming) {
        PayPeriod period = new PayPeriod();
        period.setPeriodStart(incoming.periodStart());
        period.setPeriodEnd(incoming.periodEnd());
        period.setPayDate(incoming.payDate());
        // The feed describes payslips, not period lifecycle; OPEN is the honest default.
        period.setStatus(com.payroll.demo.domain.PayPeriodStatus.OPEN);
        return payPeriods.save(period);
    }

    private Department resolveDepartment(HrmsWorker worker) {
        String code = worker.departmentCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalised = code.strip();
        return departments.findByCode(normalised).orElseGet(() -> {
            Department created = new Department();
            created.setCode(normalised);
            created.setName(mapper.departmentNameFor(worker));
            return departments.save(created);
        });
    }
}
