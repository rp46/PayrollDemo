package com.payroll.demo.web;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayPeriodStatus;
import com.payroll.demo.domain.PayslipStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
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
            BigDecimal grossPay,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            PayslipStatus status,
            List<PayslipLineView> lines) {
    }
}
