package com.payroll.demo.web;

import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.web.PayrollDtos.DepartmentView;
import com.payroll.demo.web.PayrollDtos.EmployeeView;
import com.payroll.demo.web.PayrollDtos.PayPeriodView;
import com.payroll.demo.web.PayrollDtos.PayslipView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PayrollController {

    private final PayrollService payroll;

    public PayrollController(PayrollService payroll) {
        this.payroll = payroll;
    }

    @GetMapping("/departments")
    public List<DepartmentView> departments() {
        return payroll.listDepartments();
    }

    @GetMapping("/employees")
    public List<EmployeeView> employees(@RequestParam(required = false) EmployeeStatus status) {
        return payroll.listEmployees(status);
    }

    @GetMapping("/employees/{employeeCode}")
    public EmployeeView employee(@PathVariable String employeeCode) {
        return payroll.findEmployee(employeeCode);
    }

    @GetMapping("/employees/{employeeCode}/payslips")
    public List<PayslipView> payslips(@PathVariable String employeeCode) {
        return payroll.listPayslipsFor(employeeCode);
    }

    @GetMapping("/pay-periods")
    public List<PayPeriodView> payPeriods() {
        return payroll.listPayPeriods();
    }
}
