package com.payroll.demo.dto;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayPeriodStatus;
import com.payroll.demo.domain.PayslipStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response shapes for the REST layer. Entities are never serialised directly,
 * since open-in-view is disabled and lazy associations would fail outside a transaction.
 */
public final class PayrollDtos {

    private PayrollDtos() {
    }

    public record DepartmentView(Long id, String code, String name) {
    }

    public record EmployeeView(
            Long id,
            String employeeCode,
            String firstName,
            String lastName,
            String email,
            LocalDate hireDate,
            EmployeeStatus status,
            String departmentCode,
            BigDecimal baseSalary) {
    }

    public record PayPeriodView(
            Long id,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate payDate,
            PayPeriodStatus status) {
    }

    public record PayslipLineView(
            String componentCode,
            ComponentType componentType,
            String description,
            BigDecimal amount) {
    }

    public record PayslipView(
            Long id,
            String employeeCode,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate payDate,
            BigDecimal grossPay,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            PayslipStatus status,
            List<PayslipLineView> lines) {
    }

    /**
     * Totals over the payslips in scope.
     *
     * @param averageNetPerPayslip {@code null} when there are no payslips, because the mean
     *                             of nothing is not zero
     */
    public record PaySummaryView(
            int payslipCount,
            LocalDate firstPeriodStart,
            LocalDate lastPeriodEnd,
            BigDecimal totalGross,
            BigDecimal totalDeductions,
            BigDecimal totalNet,
            BigDecimal averageNetPerPayslip) {
    }

    /** One earning or deduction code, totalled across the payslips in scope. */
    public record ComponentTotalView(
            String componentCode,
            ComponentType componentType,
            BigDecimal total,
            int occurrences) {
    }

    /**
     * An employee's pay picture: standing compensation, plus what has actually been paid.
     *
     * <p>The {@code scope*} fields echo the filters the totals were computed under, so a
     * figure can never be read without knowing what went into it.
     */
    public record PayDetailsView(
            String employeeCode,
            String firstName,
            String lastName,
            EmployeeStatus status,
            String departmentCode,
            LocalDate hireDate,
            LocalDate terminationDate,
            BigDecimal baseSalary,
            OffsetDateTime lastSyncedAt,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            PayslipStatus scopeStatus,
            PaySummaryView summary,
            List<ComponentTotalView> componentTotals,
            PayslipView latestPayslip) {
    }
}
