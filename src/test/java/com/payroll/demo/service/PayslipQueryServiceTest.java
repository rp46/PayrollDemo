package com.payroll.demo.service;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.PayrollDtos.ComponentTotalView;
import com.payroll.demo.dto.PayrollDtos.PayDetailsView;
import com.payroll.demo.dto.PayrollDtos.PayslipView;
import com.payroll.demo.dto.PayrollRows.DepartmentRow;
import com.payroll.demo.dto.PayrollRows.EmployeeRow;
import com.payroll.demo.dto.PayrollRows.PayPeriodRow;
import com.payroll.demo.dto.PayrollRows.PayrollBatch;
import com.payroll.demo.dto.PayrollRows.PayslipKey;
import com.payroll.demo.dto.PayrollRows.PayslipLineRow;
import com.payroll.demo.dto.PayrollRows.PayslipRow;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.repository.BulkPayrollRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payslip reads and derived pay details, against a real Postgres.
 *
 * <p>Two things here cannot be checked against a mock: that the optional-filter query
 * behaves when a filter is null, and that fetching the line items alongside an ordered
 * list does not return each payslip once per line. Both are asserted below.
 *
 * <p>Data is namespaced {@code IT-} / {@code 2032} and removed in {@link #cleanUp()}.
 */
@SpringBootTest
class PayslipQueryServiceTest {

    private static final String DEPT = "ITPAY";
    private static final String EMP = "IT-7001";
    private static final String OTHER_EMP = "IT-7002";

    private static final LocalDate JAN_START = LocalDate.of(2032, 1, 1);
    private static final LocalDate JAN_END = LocalDate.of(2032, 1, 31);
    private static final LocalDate FEB_START = LocalDate.of(2032, 2, 1);
    private static final LocalDate FEB_END = LocalDate.of(2032, 2, 29);
    private static final LocalDate MAR_START = LocalDate.of(2032, 3, 1);
    private static final LocalDate MAR_END = LocalDate.of(2032, 3, 31);

    @Autowired
    private PayslipQueryService service;

    @Autowired
    private BulkPayrollRepository bulk;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        cleanUp();
        bulk.applyBatch(new PayrollBatch(
                List.of(new DepartmentRow(DEPT, "Payslip Query Test")),
                List.of(employee(EMP), employee(OTHER_EMP)),
                List.of(new PayPeriodRow(JAN_START, JAN_END, LocalDate.of(2032, 2, 1)),
                        new PayPeriodRow(FEB_START, FEB_END, LocalDate.of(2032, 3, 1)),
                        new PayPeriodRow(MAR_START, MAR_END, LocalDate.of(2032, 4, 1))),
                List.of(
                        payslip(EMP, JAN_START, JAN_END, "5000.00", "500.00", "4500.00",
                                PayslipStatus.PAID, "4000.00", "1000.00", "500.00"),
                        payslip(EMP, FEB_START, FEB_END, "6000.00", "600.00", "5400.00",
                                PayslipStatus.PAID, "5000.00", "1000.00", "600.00"),
                        payslip(EMP, MAR_START, MAR_END, "7000.00", "700.00", "6300.00",
                                PayslipStatus.DRAFT, "6000.00", "1000.00", "700.00"),
                        // A second employee in the same period, for the register test.
                        payslip(OTHER_EMP, JAN_START, JAN_END, "1000.00", "100.00", "900.00",
                                PayslipStatus.PAID, "900.00", "100.00", "100.00"))));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                DELETE FROM payslip_lines WHERE payslip_id IN (
                    SELECT p.id FROM payslips p JOIN employees e ON e.id = p.employee_id
                    WHERE e.employee_code LIKE 'IT-%')""");
        jdbc.update("""
                DELETE FROM payslips WHERE employee_id IN (
                    SELECT id FROM employees WHERE employee_code LIKE 'IT-%')""");
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE 'IT-%'");
        jdbc.update("DELETE FROM departments WHERE code = ?", DEPT);
        jdbc.update("DELETE FROM pay_periods WHERE period_start >= ?", LocalDate.of(2032, 1, 1));
    }

    private static EmployeeRow employee(String code) {
        return new EmployeeRow(code, "Test", "Person", code.toLowerCase() + "@example.com",
                LocalDate.of(2030, 1, 1), null, EmployeeStatus.ACTIVE, DEPT, new BigDecimal("60000.00"));
    }

    /** Two earning lines and one deduction, so a fetch join has something to duplicate. */
    private static PayslipRow payslip(String employeeCode, LocalDate start, LocalDate end,
                                      String gross, String deductions, String net,
                                      PayslipStatus status,
                                      String basic, String hra, String tds) {
        return new PayslipRow(
                new PayslipKey(employeeCode, start, end),
                new BigDecimal(gross), new BigDecimal(deductions), new BigDecimal(net), status,
                List.of(new PayslipLineRow("BASIC", ComponentType.EARNING, "Basic pay", new BigDecimal(basic)),
                        new PayslipLineRow("HRA", ComponentType.EARNING, "Allowance", new BigDecimal(hra)),
                        new PayslipLineRow("TDS", ComponentType.DEDUCTION, "Tax", new BigDecimal(tds))));
    }

    // ------------------------------------------------------------ listing

    @Test
    @DisplayName("payslips come back once each, newest period first, with their lines")
    void listsPayslipsNewestFirstWithoutDuplicates() {
        List<PayslipView> found = service.listPayslipsFor(EMP, null, null, null);

        assertThat(found)
                .as("three payslips of three lines each must not come back as nine rows")
                .hasSize(3);
        assertThat(found).extracting(PayslipView::periodStart)
                .containsExactly(MAR_START, FEB_START, JAN_START);
        assertThat(found.getFirst().lines()).hasSize(3);
        assertThat(found.getFirst().payDate()).isEqualTo(LocalDate.of(2032, 4, 1));
        assertThat(found.getFirst().employeeCode()).isEqualTo(EMP);
    }

    @Test
    @DisplayName("lines are ordered earnings first, then by code")
    void linesAreOrdered() {
        PayslipView latest = service.latestPayslipFor(EMP);

        assertThat(latest.lines()).extracting(l -> l.componentCode())
                .containsExactly("BASIC", "HRA", "TDS");
        assertThat(latest.lines()).extracting(l -> l.componentType())
                .containsExactly(ComponentType.EARNING, ComponentType.EARNING, ComponentType.DEDUCTION);
    }

    @Test
    @DisplayName("the date window is an overlap test, not containment")
    void filtersByDateWindow() {
        assertThat(service.listPayslipsFor(EMP, FEB_START, null, null))
                .extracting(PayslipView::periodStart).containsExactly(MAR_START, FEB_START);

        assertThat(service.listPayslipsFor(EMP, null, FEB_END, null))
                .extracting(PayslipView::periodStart).containsExactly(FEB_START, JAN_START);

        assertThat(service.listPayslipsFor(EMP, FEB_START, FEB_END, null))
                .extracting(PayslipView::periodStart).containsExactly(FEB_START);

        assertThat(service.listPayslipsFor(EMP, LocalDate.of(2040, 1, 1), null, null)).isEmpty();
    }

    @Test
    @DisplayName("status narrows the listing, and a null status means all of them")
    void filtersByStatus() {
        assertThat(service.listPayslipsFor(EMP, null, null, PayslipStatus.PAID))
                .extracting(PayslipView::periodStart).containsExactly(FEB_START, JAN_START);
        assertThat(service.listPayslipsFor(EMP, null, null, PayslipStatus.DRAFT))
                .extracting(PayslipView::periodStart).containsExactly(MAR_START);
        assertThat(service.listPayslipsFor(EMP, null, null, PayslipStatus.APPROVED)).isEmpty();
        assertThat(service.listPayslipsFor(EMP, null, null, null)).hasSize(3);
    }

    @Test
    void filtersCombine() {
        assertThat(service.listPayslipsFor(EMP, JAN_START, FEB_END, PayslipStatus.PAID))
                .extracting(PayslipView::periodStart).containsExactly(FEB_START, JAN_START);
    }

    @Test
    @DisplayName("an unknown employee is a 404, not an empty list")
    void unknownEmployeeIsRejected() {
        assertThatThrownBy(() -> service.listPayslipsFor("IT-NOBODY", null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.payDetailsFor("IT-NOBODY", null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.latestPayslipFor("IT-NOBODY"))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------- single

    @Test
    void findsTheLatestPayslip() {
        assertThat(service.latestPayslipFor(EMP).periodStart()).isEqualTo(MAR_START);
    }

    @Test
    @DisplayName("an employee with no payslips is distinguishable from an unknown one")
    void latestWithoutPayslipsIs404() {
        jdbc.update("""
                DELETE FROM payslip_lines WHERE payslip_id IN (
                    SELECT p.id FROM payslips p JOIN employees e ON e.id = p.employee_id
                    WHERE e.employee_code = ?)""", EMP);
        jdbc.update("""
                DELETE FROM payslips WHERE employee_id =
                    (SELECT id FROM employees WHERE employee_code = ?)""", EMP);

        assertThatThrownBy(() -> service.latestPayslipFor(EMP))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(EMP);
    }

    @Test
    void findsAPayslipById() {
        PayslipView latest = service.latestPayslipFor(EMP);

        PayslipView byId = service.findPayslip(latest.id());

        assertThat(byId.id()).isEqualTo(latest.id());
        assertThat(byId.lines()).hasSize(3);
        assertThat(byId.netPay()).isEqualByComparingTo("6300.00");
    }

    @Test
    void unknownPayslipIdIs404() {
        assertThatThrownBy(() -> service.findPayslip(9_999_999L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a malformed id is a bad request, not a missing resource")
    void malformedIdIsRejectedAsInvalid() {
        assertThatThrownBy(() -> service.findPayslip(-1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positive id");
        assertThatThrownBy(() -> service.findPayslip(0L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.findPayslip(null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> service.listPayslipsForPeriod(-1L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a blank employee code is a bad request rather than a lookup that finds nothing")
    void blankEmployeeCodeIsRejected() {
        assertThatThrownBy(() -> service.listPayslipsFor("  ", null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("employeeCode is required");
        assertThatThrownBy(() -> service.payDetailsFor(null, null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an inverted date window is refused instead of silently matching nothing")
    void invertedRangeIsRejected() {
        assertThatThrownBy(() -> service.listPayslipsFor(EMP, MAR_START, JAN_START, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must not be before");
        assertThatThrownBy(() -> service.payDetailsFor(EMP, MAR_START, JAN_START, null))
                .isInstanceOf(ApiException.class);
    }

    // ----------------------------------------------------------- register

    @Test
    @DisplayName("the period register lists every employee paid in that run")
    void listsPayslipsForAPeriod() {
        Long janPeriodId = jdbc.queryForObject(
                "SELECT id FROM pay_periods WHERE period_start = ?", Long.class, JAN_START);

        List<PayslipView> register = service.listPayslipsForPeriod(janPeriodId);

        assertThat(register).extracting(PayslipView::employeeCode).containsExactly(EMP, OTHER_EMP);
        assertThat(register).extracting(PayslipView::periodStart).containsOnly(JAN_START);
    }

    @Test
    void unknownPeriodIs404() {
        assertThatThrownBy(() -> service.listPayslipsForPeriod(9_999_999L))
                .isInstanceOf(ApiException.class);
    }

    // -------------------------------------------------------- pay details

    @Test
    @DisplayName("pay details total every payslip in scope and break them down by component")
    void buildsPayDetails() {
        PayDetailsView details = service.payDetailsFor(EMP, null, null, null);

        assertThat(details.employeeCode()).isEqualTo(EMP);
        assertThat(details.baseSalary()).isEqualByComparingTo("60000.00");
        assertThat(details.departmentCode()).isEqualTo(DEPT);
        assertThat(details.status()).isEqualTo(EmployeeStatus.ACTIVE);

        assertThat(details.summary().payslipCount()).isEqualTo(3);
        assertThat(details.summary().firstPeriodStart()).isEqualTo(JAN_START);
        assertThat(details.summary().lastPeriodEnd()).isEqualTo(MAR_END);
        assertThat(details.summary().totalGross()).isEqualByComparingTo("18000.00");
        assertThat(details.summary().totalDeductions()).isEqualByComparingTo("1800.00");
        assertThat(details.summary().totalNet()).isEqualByComparingTo("16200.00");
        assertThat(details.summary().averageNetPerPayslip()).isEqualByComparingTo("5400.00");

        assertThat(details.componentTotals())
                .extracting(ComponentTotalView::componentCode)
                .as("earnings before deductions, then alphabetical")
                .containsExactly("BASIC", "HRA", "TDS");
        assertThat(details.componentTotals())
                .extracting(ComponentTotalView::total)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("15000.00"), new BigDecimal("3000.00"), new BigDecimal("1800.00"));
        assertThat(details.componentTotals()).allSatisfy(c -> assertThat(c.occurrences()).isEqualTo(3));

        assertThat(details.latestPayslip().periodStart()).isEqualTo(MAR_START);
    }

    @Test
    @DisplayName("totals honour the filters, and the response says which filters produced them")
    void payDetailsRespectScope() {
        PayDetailsView paidOnly = service.payDetailsFor(EMP, null, null, PayslipStatus.PAID);

        assertThat(paidOnly.scopeStatus()).isEqualTo(PayslipStatus.PAID);
        assertThat(paidOnly.summary().payslipCount()).isEqualTo(2);
        assertThat(paidOnly.summary().totalNet()).isEqualByComparingTo("9900.00");
        assertThat(paidOnly.summary().averageNetPerPayslip()).isEqualByComparingTo("4950.00");
        assertThat(paidOnly.latestPayslip().periodStart()).isEqualTo(FEB_START);

        PayDetailsView februaryOnly = service.payDetailsFor(EMP, FEB_START, FEB_END, null);
        assertThat(februaryOnly.scopeFrom()).isEqualTo(FEB_START);
        assertThat(februaryOnly.scopeTo()).isEqualTo(FEB_END);
        assertThat(februaryOnly.summary().totalNet()).isEqualByComparingTo("5400.00");
        assertThat(februaryOnly.componentTotals()).allSatisfy(c -> assertThat(c.occurrences()).isEqualTo(1));
    }

    @Test
    @DisplayName("an employee with no payslips in scope gets zeroed totals and a null average")
    void payDetailsWithNothingInScope() {
        PayDetailsView details = service.payDetailsFor(EMP, LocalDate.of(2040, 1, 1), null, null);

        assertThat(details.summary().payslipCount()).isZero();
        assertThat(details.summary().totalGross()).isEqualByComparingTo("0.00");
        assertThat(details.summary().totalNet()).isEqualByComparingTo("0.00");
        assertThat(details.summary().averageNetPerPayslip())
                .as("the mean of no payslips is undefined, not zero")
                .isNull();
        assertThat(details.summary().firstPeriodStart()).isNull();
        assertThat(details.componentTotals()).isEmpty();
        assertThat(details.latestPayslip()).isNull();

        // The employee's own details are still returned.
        assertThat(details.baseSalary()).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("gross minus deductions equals net across the whole summary, not just per payslip")
    void summaryTotalsAreInternallyConsistent() {
        var summary = service.payDetailsFor(EMP, null, null, null).summary();

        assertThat(summary.totalGross().subtract(summary.totalDeductions()))
                .isEqualByComparingTo(summary.totalNet());
    }

    @Test
    @DisplayName("the filtered query survives promotion to a server-side prepared statement")
    void filteredQueryIsStableAcrossRepeatedCalls() {
        // pgjdbc only switches to a server-side prepared statement after several executions
        // (prepareThreshold, 5 by default). A parameter whose type Postgres cannot infer
        // works until then and fails afterwards, so one call proves nothing. Drive it well
        // past the threshold with the null filters that would trip it.
        for (int i = 0; i < 20; i++) {
            assertThat(service.listPayslipsFor(EMP, null, null, null)).hasSize(3);
            assertThat(service.listPayslipsFor(EMP, JAN_START, null, null)).hasSize(3);
            assertThat(service.listPayslipsFor(EMP, null, JAN_END, null)).hasSize(1);
            assertThat(service.listPayslipsFor(EMP, null, null, PayslipStatus.PAID)).hasSize(2);
        }
    }
}
