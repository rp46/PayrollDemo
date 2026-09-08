package com.payroll.demo.dto;


import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The payroll database schema expressed as flat rows.
 *
 * <p>This is the boundary between the service and repository tiers. The service turns HRMS
 * wire DTOs into these; the repository does nothing but write them. Natural keys are used
 * throughout instead of surrogate ids, because at transform time nothing has been assigned
 * an id yet — a payslip in the batch may belong to an employee the same batch is inserting.
 */
public final class PayrollRows {

    private PayrollRows() {
    }

    /** A row for {@code departments}, keyed on {@code code}. */
    public record DepartmentRow(String code, String name) {
    }

    /** A row for {@code employees}, keyed on {@code employeeCode}. */
    public record EmployeeRow(
            String employeeCode,
            String firstName,
            String lastName,
            String email,
            LocalDate hireDate,
            LocalDate terminationDate,
            EmployeeStatus status,
            String departmentCode,
            BigDecimal baseSalary) {
    }

    /** A row for {@code pay_periods}, keyed on {@code (periodStart, periodEnd)}. */
    public record PayPeriodRow(LocalDate periodStart, LocalDate periodEnd, LocalDate payDate) {

        public PeriodKey key() {
            return new PeriodKey(periodStart, periodEnd);
        }
    }

    /** The natural key of a pay period. */
    public record PeriodKey(LocalDate periodStart, LocalDate periodEnd) {
    }

    /** The natural key of a payslip: one per employee per period. */
    public record PayslipKey(String employeeCode, LocalDate periodStart, LocalDate periodEnd) {
    }

    /** A row for {@code payslips}, carrying the {@code payslip_lines} it owns. */
    public record PayslipRow(
            PayslipKey key,
            BigDecimal grossPay,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            PayslipStatus status,
            List<PayslipLineRow> lines) {
    }

    /** A row for {@code payslip_lines}. */
    public record PayslipLineRow(
            String componentCode,
            ComponentType componentType,
            String description,
            BigDecimal amount) {
    }

    /**
     * One atomic unit of work: everything the repository writes, or rolls back, together.
     */
    public record PayrollBatch(
            List<DepartmentRow> departments,
            List<EmployeeRow> employees,
            List<PayPeriodRow> payPeriods,
            List<PayslipRow> payslips) {

        public boolean isEmpty() {
            return departments.isEmpty() && employees.isEmpty()
                    && payPeriods.isEmpty() && payslips.isEmpty();
        }

        public int totalLines() {
            return payslips.stream().mapToInt(p -> p.lines().size()).sum();
        }
    }
}
