package com.payroll.demo.repository;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.PayrollRows.DepartmentRow;
import com.payroll.demo.dto.PayrollRows.EmployeeRow;
import com.payroll.demo.dto.PayrollRows.PayPeriodRow;
import com.payroll.demo.dto.PayrollRows.PayrollBatch;
import com.payroll.demo.dto.PayrollRows.PayslipKey;
import com.payroll.demo.dto.PayrollRows.PayslipLineRow;
import com.payroll.demo.dto.PayrollRows.PayslipRow;
import com.payroll.demo.dto.PayrollRows.PeriodKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transaction boundary, against a real Postgres.
 *
 * <p>Rollback is the whole point of this tier, and it cannot be demonstrated against a
 * mock: only a real constraint violation inside a real transaction proves that a failure
 * on the last row undoes the first.
 *
 * <p>Every row this test writes is namespaced with an {@code IT-} prefix and removed in
 * {@link #cleanUp()}, so the database is left exactly as it was found.
 */
@SpringBootTest
class BulkPayrollRepositoryTest {

    private static final String DEPT = "ITDEPT";
    private static final String EMP_1 = "IT-9001";
    private static final String EMP_2 = "IT-9002";
    private static final LocalDate START = LocalDate.of(2031, 3, 1);
    private static final LocalDate END = LocalDate.of(2031, 3, 31);
    private static final LocalDate PAY_DATE = LocalDate.of(2031, 4, 1);

    @Autowired
    private BulkPayrollRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

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
        jdbc.update("DELETE FROM pay_periods WHERE period_start = ?", START);
    }

    private static EmployeeRow employee(String code, BigDecimal salary) {
        return new EmployeeRow(code, "Test", "Person", code.toLowerCase() + "@example.com",
                LocalDate.of(2020, 1, 1), null, EmployeeStatus.ACTIVE, DEPT, salary);
    }

    private static PayslipRow payslip(String employeeCode, String gross, String deductions, String net,
                                      List<PayslipLineRow> lines) {
        return new PayslipRow(new PayslipKey(employeeCode, START, END),
                new BigDecimal(gross), new BigDecimal(deductions), new BigDecimal(net),
                PayslipStatus.PAID, lines);
    }

    private static PayrollBatch batch(List<EmployeeRow> employees, List<PayslipRow> payslips) {
        return new PayrollBatch(
                List.of(new DepartmentRow(DEPT, "Integration Test Dept")),
                employees,
                List.of(new PayPeriodRow(START, END, PAY_DATE)),
                payslips);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int employeeCount() {
        return count("SELECT count(*) FROM employees WHERE employee_code LIKE 'IT-%'");
    }

    // ------------------------------------------------------------------ commit

    @Test
    @DisplayName("a valid batch commits every table together")
    void batchCommits() {
        var lines = List.of(
                new PayslipLineRow("BASIC", ComponentType.EARNING, "Basic pay", new BigDecimal("5000.00")),
                new PayslipLineRow("TDS", ComponentType.DEDUCTION, "Tax", new BigDecimal("500.00")));

        var counts = repository.applyBatch(batch(
                List.of(employee(EMP_1, new BigDecimal("60000.00")), employee(EMP_2, new BigDecimal("70000.00"))),
                List.of(payslip(EMP_1, "5000.00", "500.00", "4500.00", lines))));

        assertThat(counts.employees()).isEqualTo(2);
        assertThat(counts.payslips()).isEqualTo(1);
        assertThat(counts.payslipLines()).isEqualTo(2);

        assertThat(employeeCount()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM departments WHERE code = ?", DEPT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pay_periods WHERE period_start = ?", START)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM payslip_lines l JOIN payslips p ON p.id = l.payslip_id
                JOIN employees e ON e.id = p.employee_id WHERE e.employee_code = ?""", EMP_1)).isEqualTo(2);

        // last_synced_at proves the write went through this path.
        assertThat(count("SELECT count(*) FROM employees WHERE employee_code = ? AND last_synced_at IS NOT NULL",
                EMP_1)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- rollback

    @Test
    @DisplayName("a constraint violation on the last row rolls back the first")
    void batchRollsBackEntirelyOnConstraintViolation() {
        // employees_base_salary_chk rejects a negative salary. The first employee is
        // perfectly valid and is written before the second one fails.
        PayrollBatch poisoned = batch(
                List.of(employee(EMP_1, new BigDecimal("60000.00")),
                        employee(EMP_2, new BigDecimal("-1.00"))),
                List.of());

        assertThatThrownBy(() -> repository.applyBatch(poisoned))
                .isInstanceOf(DataAccessException.class);

        assertThat(employeeCount())
                .as("the valid employee must not survive the failed batch")
                .isZero();
        assertThat(count("SELECT count(*) FROM departments WHERE code = ?", DEPT))
                .as("the department written earlier in the same transaction must be gone too")
                .isZero();
        assertThat(count("SELECT count(*) FROM pay_periods WHERE period_start = ?", START))
                .isZero();
    }

    @Test
    @DisplayName("a payslip failure rolls back the employees written earlier in the same batch")
    void payslipFailureRollsBackEmployees() {
        // netPay is not checked by the schema, but total_deductions must be >= 0.
        PayrollBatch poisoned = batch(
                List.of(employee(EMP_1, new BigDecimal("60000.00"))),
                List.of(payslip(EMP_1, "5000.00", "-500.00", "5500.00", List.of())));

        assertThatThrownBy(() -> repository.applyBatch(poisoned))
                .isInstanceOf(DataAccessException.class);

        assertThat(employeeCount())
                .as("employees committed earlier in the transaction must be rolled back")
                .isZero();
    }

    @Test
    @DisplayName("a rolled-back batch leaves pre-existing rows untouched")
    void rollbackDoesNotDisturbExistingRows() {
        repository.applyBatch(batch(List.of(employee(EMP_1, new BigDecimal("60000.00"))), List.of()));
        assertThat(employeeCount()).isEqualTo(1);

        PayrollBatch poisoned = batch(
                List.of(employee(EMP_1, new BigDecimal("99999.00")),
                        employee(EMP_2, new BigDecimal("-1.00"))),
                List.of());

        assertThatThrownBy(() -> repository.applyBatch(poisoned)).isInstanceOf(DataAccessException.class);

        assertThat(employeeCount()).as("the earlier committed row survives").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT base_salary FROM employees WHERE employee_code = ?", BigDecimal.class, EMP_1))
                .as("and its value is the committed one, not the rolled-back update")
                .isEqualByComparingTo("60000.00");
    }

    // ------------------------------------------------------------- idempotency

    @Test
    @DisplayName("re-applying the same batch updates in place instead of duplicating")
    void reapplyingIsIdempotent() {
        var lines = List.of(
                new PayslipLineRow("BASIC", ComponentType.EARNING, "Basic pay", new BigDecimal("5000.00")));
        PayrollBatch b = batch(List.of(employee(EMP_1, new BigDecimal("60000.00"))),
                List.of(payslip(EMP_1, "5000.00", "0.00", "5000.00", lines)));

        repository.applyBatch(b);
        repository.applyBatch(b);
        repository.applyBatch(b);

        assertThat(employeeCount()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pay_periods WHERE period_start = ?", START)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM payslips p JOIN employees e ON e.id = p.employee_id
                WHERE e.employee_code = ?""", EMP_1)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM payslip_lines l JOIN payslips p ON p.id = l.payslip_id
                JOIN employees e ON e.id = p.employee_id WHERE e.employee_code = ?""", EMP_1))
                .as("lines are replaced, not appended, on every re-apply")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an updated batch overwrites the stored values")
    void reapplyingUpdatesValues() {
        repository.applyBatch(batch(List.of(employee(EMP_1, new BigDecimal("60000.00"))), List.of()));
        repository.applyBatch(batch(List.of(employee(EMP_1, new BigDecimal("75000.00"))), List.of()));

        assertThat(jdbc.queryForObject(
                "SELECT base_salary FROM employees WHERE employee_code = ?", BigDecimal.class, EMP_1))
                .isEqualByComparingTo("75000.00");
    }

    @Test
    @DisplayName("a line removed upstream disappears rather than lingering")
    void removedLinesAreDeleted() {
        var twoLines = List.of(
                new PayslipLineRow("BASIC", ComponentType.EARNING, "Basic", new BigDecimal("4000.00")),
                new PayslipLineRow("HRA", ComponentType.EARNING, "Allowance", new BigDecimal("1000.00")));
        repository.applyBatch(batch(List.of(employee(EMP_1, new BigDecimal("60000.00"))),
                List.of(payslip(EMP_1, "5000.00", "0.00", "5000.00", twoLines))));

        var oneLine = List.of(
                new PayslipLineRow("BASIC", ComponentType.EARNING, "Basic", new BigDecimal("5000.00")));
        repository.applyBatch(batch(List.of(employee(EMP_1, new BigDecimal("60000.00"))),
                List.of(payslip(EMP_1, "5000.00", "0.00", "5000.00", oneLine))));

        assertThat(jdbc.queryForList("""
                SELECT l.component_code FROM payslip_lines l
                JOIN payslips p ON p.id = l.payslip_id
                JOIN employees e ON e.id = p.employee_id WHERE e.employee_code = ?""", String.class, EMP_1))
                .containsExactly("BASIC");
    }

    @Test
    @DisplayName("an existing pay period keeps its own status and pay date")
    void existingPayPeriodIsNotOverwritten() {
        repository.applyBatch(batch(List.of(), List.of()));
        jdbc.update("UPDATE pay_periods SET status = 'CLOSED' WHERE period_start = ?", START);

        repository.applyBatch(batch(List.of(), List.of()));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM pay_periods WHERE period_start = ?", String.class, START))
                .as("a payslip feed does not own period lifecycle")
                .isEqualTo("CLOSED");
    }

    // -------------------------------------------------------------------- reads

    @Test
    @DisplayName("existence lookups answer for the whole set in one pass")
    void existenceLookups() {
        repository.applyBatch(batch(List.of(employee(EMP_1, new BigDecimal("60000.00"))), List.of()));

        assertThat(repository.findExistingEmployeeCodes(List.of(EMP_1, EMP_2, "IT-NOPE")))
                .containsExactly(EMP_1);
        assertThat(repository.findExistingPeriods(List.of(
                new PeriodKey(START, END),
                new PeriodKey(LocalDate.of(1999, 1, 1), LocalDate.of(1999, 1, 31)))))
                .containsExactly(new PeriodKey(START, END));
        assertThat(repository.findExistingEmployeeCodes(Set.of())).isEmpty();
        assertThat(repository.findExistingPeriods(Set.of())).isEmpty();
    }
}
