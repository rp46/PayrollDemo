package com.payroll.demo.controller;

import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.dto.PayrollDtos.DepartmentView;
import com.payroll.demo.dto.PayrollDtos.EmployeeView;
import com.payroll.demo.dto.PayrollDtos.PayPeriodView;
import com.payroll.demo.service.PayrollQueryService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Departments, employees and pay periods. Payslips and pay details live in
 * {@link PayslipController}.
 */
@RestController
@RequestMapping("/api")
public class PayrollController {

    private final PayrollQueryService payrollQueryService;

    public PayrollController(PayrollQueryService payrollQueryService) {
        this.payrollQueryService = payrollQueryService;
    }

    @GetMapping("/departments")
    public List<DepartmentView> departments() {
        return payrollQueryService.listDepartments();
    }

    @GetMapping("/employees")
    public List<EmployeeView> employees(@RequestParam(required = false) EmployeeStatus status) {
        return payrollQueryService.listEmployees(status);
    }

    @GetMapping("/employees/{employeeCode}")
    public EmployeeView employee(@PathVariable String employeeCode) {
        return payrollQueryService.findEmployee(employeeCode);
    }

    @GetMapping("/pay-periods")
    public List<PayPeriodView> payPeriods() {
        return payrollQueryService.listPayPeriods();
    }
}
