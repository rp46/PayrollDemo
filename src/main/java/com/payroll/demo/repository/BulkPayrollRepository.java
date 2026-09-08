package com.payroll.demo.repository;

import com.payroll.demo.dto.BulkWriteCounts;
import com.payroll.demo.dto.PayrollRows.PayrollBatch;
import com.payroll.demo.dto.PayrollRows.PayslipKey;
import com.payroll.demo.dto.PayrollRows.PayslipLineRow;
import com.payroll.demo.dto.PayrollRows.PayslipRow;
import com.payroll.demo.dto.PayrollRows.PeriodKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes a whole {@link PayrollBatch} into the payroll schema, atomically.
 *
 * <p>{@link #applyBatch} is the transaction boundary: all six statements below either
 * commit together or roll back together, so a failure on the last payslip line cannot
 * leave half a payroll import behind. It sits here rather than on the calling service
 * because Spring proxies do not intercept self-invocation — the service fetches over HTTP
 * first and must not hold a database connection open while it does. Spring Data
 * repositories are transactional by default for the same reason, so this is consistent
 * with the rest of the project.
 *
 * <p>Every write is an idempotent {@code INSERT ... ON CONFLICT} on a natural key, batched
 * per table. Re-applying the same batch converges to the same state.
 */
@Repository
public class BulkPayrollRepository {

    private static final Logger log = LoggerFactory.getLogger(BulkPayrollRepository.class);

    /** Statements per round trip. Keeps a very large feed from building one enormous batch. */
    private static final int CHUNK_SIZE = 500;

    private static final String UPSERT_DEPARTMENT = """
            INSERT INTO departments (code, name)
            VALUES (?, ?)
            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
            """;

    private static final String UPSERT_EMPLOYEE = """
            INSERT INTO employees (employee_code, first_name, last_name, email, hire_date,
                                   termination_date, status, department_id, base_salary, last_synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, (SELECT id FROM departments WHERE code = ?), ?, now())
            ON CONFLICT (employee_code) DO UPDATE SET
                first_name       = EXCLUDED.first_name,
                last_name        = EXCLUDED.last_name,
                email            = EXCLUDED.email,
                hire_date        = EXCLUDED.hire_date,
                termination_date = EXCLUDED.termination_date,
                status           = EXCLUDED.status,
                department_id    = EXCLUDED.department_id,
                base_salary      = EXCLUDED.base_salary,
                last_synced_at   = now()
            """;

    // DO NOTHING, not DO UPDATE: a payslip feed describes payslips, not the lifecycle of a
    // period that already exists. Overwriting a CLOSED period back to OPEN would be wrong.
    private static final String UPSERT_PAY_PERIOD = """
            INSERT INTO pay_periods (period_start, period_end, pay_date, status)
            VALUES (?, ?, ?, 'OPEN')
            ON CONFLICT (period_start, period_end) DO NOTHING
            """;

    private static final String UPSERT_PAYSLIP = """
            INSERT INTO payslips (employee_id, pay_period_id, gross_pay, total_deductions,
                                  net_pay, status, last_synced_at)
            VALUES ((SELECT id FROM employees WHERE employee_code = ?),
                    (SELECT id FROM pay_periods WHERE period_start = ? AND period_end = ?),
                    ?, ?, ?, ?, now())
            ON CONFLICT (employee_id, pay_period_id) DO UPDATE SET
                gross_pay        = EXCLUDED.gross_pay,
                total_deductions = EXCLUDED.total_deductions,
                net_pay          = EXCLUDED.net_pay,
                status           = EXCLUDED.status,
                last_synced_at   = now()
            """;

    /** Resolves a payslip by its natural key; shared by the line delete and insert. */
    private static final String PAYSLIP_ID_BY_KEY = """
            SELECT p.id FROM payslips p
              JOIN employees e ON e.id = p.employee_id
              JOIN pay_periods pp ON pp.id = p.pay_period_id
             WHERE e.employee_code = ? AND pp.period_start = ? AND pp.period_end = ?
            """;

    private static final String DELETE_PAYSLIP_LINES =
            "DELETE FROM payslip_lines WHERE payslip_id = (" + PAYSLIP_ID_BY_KEY + ")";

    private static final String INSERT_PAYSLIP_LINE = """
            INSERT INTO payslip_lines (payslip_id, component_code, component_type, description, amount)
            VALUES ((""" + PAYSLIP_ID_BY_KEY + "), ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public BulkPayrollRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Applies the whole batch in one transaction.
     *
     * <p>Ordered so foreign keys always resolve: departments and periods first, then the
     * employees that point at departments, then the payslips that point at both, then the
     * lines. Lines are deleted and re-inserted rather than merged, because the HRMS is
     * authoritative for the breakdown and a merge would strand a component it removed.
     *
     * @throws org.springframework.dao.DataAccessException on any failure, having rolled the
     *                                                     entire batch back
     */
    @Transactional
    public BulkWriteCounts applyBatch(PayrollBatch batch) {
        int departments = batchUpdate(UPSERT_DEPARTMENT, batch.departments(), (ps, row) -> {
            ps.setString(1, row.code());
            ps.setString(2, row.name());
        });

        int periods = batchUpdate(UPSERT_PAY_PERIOD, batch.payPeriods(), (ps, row) -> {
            setDate(ps, 1, row.periodStart());
            setDate(ps, 2, row.periodEnd());
            setDate(ps, 3, row.payDate());
        });

        int employees = batchUpdate(UPSERT_EMPLOYEE, batch.employees(), (ps, row) -> {
            ps.setString(1, row.employeeCode());
            ps.setString(2, row.firstName());
            ps.setString(3, row.lastName());
            ps.setString(4, row.email());
            setDate(ps, 5, row.hireDate());
            setDate(ps, 6, row.terminationDate());
            ps.setString(7, row.status().name());
            ps.setString(8, row.departmentCode());
            ps.setBigDecimal(9, row.baseSalary());
        });

        int payslips = batchUpdate(UPSERT_PAYSLIP, batch.payslips(), (ps, row) -> {
            PayslipKey key = row.key();
            ps.setString(1, key.employeeCode());
            setDate(ps, 2, key.periodStart());
            setDate(ps, 3, key.periodEnd());
            ps.setBigDecimal(4, row.grossPay());
            ps.setBigDecimal(5, row.totalDeductions());
            ps.setBigDecimal(6, row.netPay());
            ps.setString(7, row.status().name());
        });

        int lines = replaceLines(batch.payslips());

        BulkWriteCounts counts = new BulkWriteCounts(departments, employees, periods, payslips, lines);
        log.info("Bulk upsert applied: {}", counts);
        return counts;
    }

    /** Clears the existing breakdown for every payslip in the batch, then writes the new one. */
    private int replaceLines(List<PayslipRow> payslips) {
        if (payslips.isEmpty()) {
            return 0;
        }
        batchUpdate(DELETE_PAYSLIP_LINES, payslips, (ps, row) -> bindKey(ps, row.key()));

        List<LineWithKey> lines = new ArrayList<>();
        for (PayslipRow payslip : payslips) {
            for (PayslipLineRow line : payslip.lines()) {
                lines.add(new LineWithKey(payslip.key(), line));
            }
        }
        return batchUpdate(INSERT_PAYSLIP_LINE, lines, (ps, entry) -> {
            bindKey(ps, entry.key());
            ps.setString(4, entry.line().componentCode());
            ps.setString(5, entry.line().componentType().name());
            ps.setString(6, entry.line().description());
            ps.setBigDecimal(7, entry.line().amount());
        });
    }

    // ------------------------------------------------------------------- reads

    /**
     * Which of these periods the database already has.
     *
     * <p>The service needs this before transforming: a {@code payDate} is only mandatory
     * when the period has to be created, and inventing one for payroll is not acceptable.
     */
    @Transactional(readOnly = true)
    public Set<PeriodKey> findExistingPeriods(Collection<PeriodKey> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<PeriodKey> distinct = List.copyOf(new HashSet<>(candidates));
        Set<PeriodKey> found = new HashSet<>();

        for (int start = 0; start < distinct.size(); start += CHUNK_SIZE) {
            List<PeriodKey> chunk = distinct.subList(start, Math.min(start + CHUNK_SIZE, distinct.size()));
            // Row-value IN, so the whole chunk is one round trip rather than one per period.
            String sql = "SELECT period_start, period_end FROM pay_periods WHERE (period_start, period_end) IN ("
                    + String.join(", ", java.util.Collections.nCopies(chunk.size(), "(?, ?)")) + ")";

            Object[] args = new Object[chunk.size() * 2];
            for (int i = 0; i < chunk.size(); i++) {
                args[i * 2] = chunk.get(i).periodStart();
                args[i * 2 + 1] = chunk.get(i).periodEnd();
            }
            jdbc.query(sql, rs -> {
                found.add(new PeriodKey(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)));
            }, args);
        }
        return found;
    }

    /** Which of these employee codes already exist, so a payslip for an unknown worker can be rejected cleanly. */
    @Transactional(readOnly = true)
    public Set<String> findExistingEmployeeCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        List<String> distinct = List.copyOf(new HashSet<>(codes));
        Set<String> found = new HashSet<>();

        for (int start = 0; start < distinct.size(); start += CHUNK_SIZE) {
            List<String> chunk = distinct.subList(start, Math.min(start + CHUNK_SIZE, distinct.size()));
            String sql = "SELECT employee_code FROM employees WHERE employee_code IN ("
                    + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
            found.addAll(jdbc.queryForList(sql, String.class, chunk.toArray()));
        }
        return found;
    }

    // ------------------------------------------------------------------ helpers

    private <T> int batchUpdate(String sql, List<T> rows, RowBinder<T> binder) {
        if (rows.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (int start = 0; start < rows.size(); start += CHUNK_SIZE) {
            List<T> chunk = rows.subList(start, Math.min(start + CHUNK_SIZE, rows.size()));
            // Returns one int[] per executed batch, hence the nesting.
            int[][] results = jdbc.batchUpdate(sql, chunk, chunk.size(),
                    (ps, row) -> binder.bind(ps, row));
            for (int[] batchResult : results) {
                for (int result : batchResult) {
                    // Postgres reports SUCCESS_NO_INFO (-2) for some batched statements;
                    // count the row as applied rather than let a sentinel corrupt the total.
                    affected += result < 0 ? 1 : result;
                }
            }
        }
        return affected;
    }

    private static void bindKey(PreparedStatement ps, PayslipKey key) throws SQLException {
        ps.setString(1, key.employeeCode());
        setDate(ps, 2, key.periodStart());
        setDate(ps, 3, key.periodEnd());
    }

    private static void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setObject(index, value);
        }
    }

    @FunctionalInterface
    private interface RowBinder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }

    private record LineWithKey(PayslipKey key, PayslipLineRow line) {
    }

}
