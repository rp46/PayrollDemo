package com.payroll.demo.ingestion;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslip;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.hrms.HrmsDtos.HrmsWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrmsRecordMapperTest {

    private final HrmsRecordMapper mapper = new HrmsRecordMapper();

    private static HrmsWorker worker() {
        return new HrmsWorker("E-2001", "Priya", "Raman", "priya.raman@example.com",
                LocalDate.of(2023, 4, 1), null, "ACTIVE", "ENG", "Engineering",
                new BigDecimal("1200000.00"));
    }

    private static HrmsPayslip payslip(List<HrmsPayslipLine> lines) {
        return new HrmsPayslip("PS-1", "E-2001",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1),
                new BigDecimal("100000.00"), new BigDecimal("18000.00"), new BigDecimal("82000.00"),
                "PAID", lines);
    }

    private static List<HrmsPayslipLine> balancedLines() {
        return List.of(
                new HrmsPayslipLine("BASIC", "EARNING", "Basic pay", new BigDecimal("100000.00")),
                new HrmsPayslipLine("TDS", "DEDUCTION", "Withholding tax", new BigDecimal("18000.00")));
    }

    // ------------------------------------------------------------------ workers

    @Test
    void acceptsAWellFormedWorker() {
        assertThatCode(() -> mapper.validateWorker(worker())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing salary is rejected rather than defaulted to zero")
    void missingSalaryIsRejected() {
        HrmsWorker broken = new HrmsWorker("E-2001", "Priya", "Raman", "p@example.com",
                LocalDate.of(2023, 4, 1), null, "ACTIVE", "ENG", "Engineering", null);

        assertThatThrownBy(() -> mapper.validateWorker(broken))
                .isInstanceOfSatisfying(IngestionRecordException.class, ex -> {
                    assertThat(ex.getRecordType()).isEqualTo(IngestionRecordType.WORKER);
                    assertThat(ex.getExternalId()).isEqualTo("E-2001");
                    assertThat(ex).hasMessageContaining("baseSalary is required");
                });
    }

    @Test
    void negativeSalaryIsRejected() {
        HrmsWorker broken = new HrmsWorker("E-2001", "Priya", "Raman", "p@example.com",
                LocalDate.of(2023, 4, 1), null, "ACTIVE", "ENG", "Engineering", new BigDecimal("-1"));

        assertThatThrownBy(() -> mapper.validateWorker(broken))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("baseSalary is negative");
    }

    @Test
    @DisplayName("a worker id longer than the column is caught here, not by the driver")
    void oversizedWorkerIdIsRejected() {
        HrmsWorker broken = new HrmsWorker("E-".repeat(20), "Priya", "Raman", "p@example.com",
                LocalDate.of(2023, 4, 1), null, "ACTIVE", "ENG", "Engineering", BigDecimal.TEN);

        assertThatThrownBy(() -> mapper.validateWorker(broken))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("workerId exceeds 20 characters");
    }

    @Test
    void terminationBeforeHireIsRejected() {
        HrmsWorker broken = new HrmsWorker("E-2001", "Priya", "Raman", "p@example.com",
                LocalDate.of(2023, 4, 1), LocalDate.of(2022, 1, 1), "TERMINATED", "ENG", "Engineering",
                BigDecimal.TEN);

        assertThatThrownBy(() -> mapper.validateWorker(broken))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("precedes hireDate");
    }

    @Test
    @DisplayName("status synonyms map, and an unknown one is rejected instead of guessed")
    void employmentStatusMapping() {
        assertThat(mapper.toEmployeeStatus("E-1", "Active")).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(mapper.toEmployeeStatus("E-1", "on-leave")).isEqualTo(EmployeeStatus.ON_LEAVE);
        assertThat(mapper.toEmployeeStatus("E-1", "LOA")).isEqualTo(EmployeeStatus.ON_LEAVE);
        assertThat(mapper.toEmployeeStatus("E-1", "terminated")).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(mapper.toEmployeeStatus("E-1", null)).isEqualTo(EmployeeStatus.ACTIVE);

        assertThatThrownBy(() -> mapper.toEmployeeStatus("E-1", "RETIRED_MAYBE"))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("unrecognised employmentStatus");
    }

    @Test
    void departmentNameFallsBackToTheCode() {
        HrmsWorker noName = new HrmsWorker("E-2001", "Priya", "Raman", "p@example.com",
                LocalDate.of(2023, 4, 1), null, "ACTIVE", "ENG", null, BigDecimal.TEN);

        assertThat(mapper.departmentNameFor(noName)).isEqualTo("ENG");
    }

    // ----------------------------------------------------------------- payslips

    @Test
    void acceptsABalancedPayslip() {
        assertThatCode(() -> mapper.validatePayslip(payslip(balancedLines()), true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("net pay that does not equal gross minus deductions is arithmetic nonsense")
    void inconsistentTotalsAreRejected() {
        HrmsPayslip broken = new HrmsPayslip("PS-1", "E-2001",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1),
                new BigDecimal("100000.00"), new BigDecimal("18000.00"), new BigDecimal("99000.00"),
                "PAID", List.of());

        assertThatThrownBy(() -> mapper.validatePayslip(broken, true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("does not equal grossPay");
    }

    @Test
    @DisplayName("line items must add up to the stated totals")
    void unbalancedLinesAreRejected() {
        List<HrmsPayslipLine> lines = List.of(
                new HrmsPayslipLine("BASIC", "EARNING", "Basic pay", new BigDecimal("90000.00")),
                new HrmsPayslipLine("TDS", "DEDUCTION", "Tax", new BigDecimal("18000.00")));

        assertThatThrownBy(() -> mapper.validatePayslip(payslip(lines), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("earning lines total 90000.00 but grossPay is 100000.00");
    }

    @Test
    @DisplayName("scale differences are not treated as mismatches")
    void differentScalesStillBalance() {
        List<HrmsPayslipLine> lines = List.of(
                new HrmsPayslipLine("BASIC", "EARNING", "Basic pay", new BigDecimal("100000")),
                new HrmsPayslipLine("TDS", "DEDUCTION", "Tax", new BigDecimal("18000.0")));

        assertThatCode(() -> mapper.validatePayslip(payslip(lines), true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a payslip with no breakdown is accepted on its totals alone")
    void payslipWithoutLinesIsAccepted() {
        assertThatCode(() -> mapper.validatePayslip(payslip(List.of()), true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("payDate is only mandatory when the pay period has to be created")
    void payDateRequiredOnlyForNewPeriods() {
        HrmsPayslip noPayDate = new HrmsPayslip("PS-1", "E-2001",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null,
                new BigDecimal("100000.00"), new BigDecimal("18000.00"), new BigDecimal("82000.00"),
                "PAID", List.of());

        assertThatThrownBy(() -> mapper.validatePayslip(noPayDate, true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("payDate is required");

        // The period already exists, so its stored pay date stands and nothing is invented.
        assertThatCode(() -> mapper.validatePayslip(noPayDate, false)).doesNotThrowAnyException();
    }

    @Test
    void reversedPeriodIsRejected() {
        HrmsPayslip broken = new HrmsPayslip("PS-1", "E-2001",
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "PAID", List.of());

        assertThatThrownBy(() -> mapper.validatePayslip(broken, true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("precedes periodStart");
    }

    @Test
    void negativeLineAmountIsRejected() {
        List<HrmsPayslipLine> lines = List.of(
                new HrmsPayslipLine("BASIC", "EARNING", "Basic", new BigDecimal("-5")));

        assertThatThrownBy(() -> mapper.validatePayslip(payslip(lines), true))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void componentTypeMapping() {
        assertThat(mapper.toComponentType("PS-1", "earnings", "BASIC")).isEqualTo(ComponentType.EARNING);
        assertThat(mapper.toComponentType("PS-1", "TAX", "TDS")).isEqualTo(ComponentType.DEDUCTION);

        assertThatThrownBy(() -> mapper.toComponentType("PS-1", "REIMBURSEMENT?", "MISC"))
                .isInstanceOf(IngestionRecordException.class)
                .hasMessageContaining("unrecognised componentType");
    }

    @Test
    void payslipStatusMapping() {
        assertThat(mapper.toPayslipStatus("PS-1", "paid")).isEqualTo(PayslipStatus.PAID);
        assertThat(mapper.toPayslipStatus("PS-1", "Confirmed")).isEqualTo(PayslipStatus.APPROVED);
        assertThat(mapper.toPayslipStatus("PS-1", null)).isEqualTo(PayslipStatus.DRAFT);

        assertThatThrownBy(() -> mapper.toPayslipStatus("PS-1", "VOIDED"))
                .isInstanceOf(IngestionRecordException.class);
    }

    @Test
    void nullRecordsAreRejectedNotDereferenced() {
        assertThatThrownBy(() -> mapper.validateWorker(null))
                .isInstanceOf(IngestionRecordException.class);
        assertThatThrownBy(() -> mapper.validatePayslip(null, true))
                .isInstanceOf(IngestionRecordException.class);
    }
}
