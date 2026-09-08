package com.payroll.demo.web;

import com.payroll.demo.domain.Employee;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayPeriod;
import com.payroll.demo.domain.Payslip;
import com.payroll.demo.repository.DepartmentRepository;
import com.payroll.demo.repository.EmployeeRepository;
import com.payroll.demo.repository.PayPeriodRepository;
import com.payroll.demo.repository.PayslipRepository;
import com.payroll.demo.web.PayrollDtos.DepartmentView;
import com.payroll.demo.web.PayrollDtos.EmployeeView;
import com.payroll.demo.web.PayrollDtos.PayPeriodView;
import com.payroll.demo.web.PayrollDtos.PayslipLineView;
import com.payroll.demo.web.PayrollDtos.PayslipView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class PayrollService {

    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final PayPeriodRepository payPeriods;
    private final PayslipRepository payslips;

    public PayrollService(DepartmentRepository departments,
                          EmployeeRepository employees,
                          PayPeriodRepository payPeriods,
                          PayslipRepository payslips) {
        this.departments = departments;
        this.employees = employees;
        this.payPeriods = payPeriods;
        this.payslips = payslips;
    }

    public List<DepartmentView> listDepartments() {
        return departments.findAll().stream()
                .map(d -> new DepartmentView(d.getId(), d.getCode(), d.getName()))
                .toList();
    }

    public List<EmployeeView> listEmployees(EmployeeStatus status) {
        List<Employee> found = (status == null)
                ? employees.findAllByOrderByEmployeeCodeAsc()
                : employees.findByStatusOrderByEmployeeCodeAsc(status);
        return found.stream().map(PayrollService::toView).toList();
    }

    public EmployeeView findEmployee(String employeeCode) {
        return employees.findByEmployeeCode(employeeCode)
                .map(PayrollService::toView)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeCode));
    }

    public List<PayPeriodView> listPayPeriods() {
        return payPeriods.findAllByOrderByPeriodStartDesc().stream()
                .map(PayrollService::toView)
                .toList();
    }

    public List<PayslipView> listPayslipsFor(String employeeCode) {
        if (employees.findByEmployeeCode(employeeCode).isEmpty()) {
            throw new EmployeeNotFoundException(employeeCode);
        }
        return payslips.findByEmployeeEmployeeCode(employeeCode).stream()
                .map(PayrollService::toView)
                .toList();
    }

    private static EmployeeView toView(Employee e) {
        return new EmployeeView(
                e.getId(),
                e.getEmployeeCode(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getHireDate(),
                e.getStatus(),
                e.getDepartment() == null ? null : e.getDepartment().getCode(),
                e.getBaseSalary());
    }

    private static PayPeriodView toView(PayPeriod p) {
        return new PayPeriodView(p.getId(), p.getPeriodStart(), p.getPeriodEnd(), p.getPayDate(), p.getStatus());
    }

    private static PayslipView toView(Payslip p) {
        List<PayslipLineView> lines = p.getLines().stream()
                .map(l -> new PayslipLineView(
                        l.getComponentCode(), l.getComponentType(), l.getDescription(), l.getAmount()))
                .toList();
        return new PayslipView(
                p.getId(),
                p.getEmployee().getEmployeeCode(),
                p.getPayPeriod().getPeriodStart(),
                p.getPayPeriod().getPeriodEnd(),
                p.getGrossPay(),
                p.getTotalDeductions(),
                p.getNetPay(),
                p.getStatus(),
                lines);
    }
}
