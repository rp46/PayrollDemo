package com.payroll.demo.service;

import com.payroll.demo.domain.Employee;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayPeriod;
import com.payroll.demo.dto.PayrollDtos.DepartmentView;
import com.payroll.demo.dto.PayrollDtos.EmployeeView;
import com.payroll.demo.dto.PayrollDtos.PayPeriodView;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.repository.DepartmentRepository;
import com.payroll.demo.repository.EmployeeRepository;
import com.payroll.demo.repository.PayPeriodRepository;
import com.payroll.demo.util.ServiceGuard;
import com.payroll.demo.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads departments, employees and pay periods.
 *
 * <p>Every method runs through {@link ServiceGuard}, so each one logs its start and finish
 * and never lets a raw exception reach the controller. Payslips and pay details are
 * {@link PayslipQueryService}.
 */
@Service
@Transactional(readOnly = true)
public class PayrollQueryService {

    private static final Logger log = LoggerFactory.getLogger(PayrollQueryService.class);

    private static final int MAX_EMPLOYEE_CODE = 20;

    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final PayPeriodRepository payPeriods;

    public PayrollQueryService(DepartmentRepository departments,
                               EmployeeRepository employees,
                               PayPeriodRepository payPeriods) {
        this.departments = departments;
        this.employees = employees;
        this.payPeriods = payPeriods;
    }

    public List<DepartmentView> listDepartments() {
        return ServiceGuard.call(log, "list departments", () -> {
            List<DepartmentView> found = departments.findAll().stream()
                    .map(d -> new DepartmentView(d.getId(), d.getCode(), d.getName()))
                    .toList();
            log.info("Found {} department(s)", found.size());
            return found;
        });
    }

    public List<EmployeeView> listEmployees(EmployeeStatus status) {
        return ServiceGuard.call(log, "list employees (status=" + status + ")", () -> {
            List<Employee> found = (status == null)
                    ? employees.findAllByOrderByEmployeeCodeAsc()
                    : employees.findByStatusOrderByEmployeeCodeAsc(status);

            log.info("Found {} employee(s) for status filter {}", found.size(), status);
            return found.stream().map(PayrollQueryService::toView).toList();
        });
    }

    public EmployeeView findEmployee(String employeeCode) {
        String code = Validate.requireText(employeeCode, "employeeCode");
        Validate.optionalText(code, "employeeCode", MAX_EMPLOYEE_CODE);

        return ServiceGuard.call(log, "find employee " + code, () -> employees.findByEmployeeCode(code)
                .map(PayrollQueryService::toView)
                .orElseThrow(() -> {
                    log.warn("No employee stored with code {}", code);
                    return ApiException.notFound("No employee with code " + code);
                }));
    }

    public List<PayPeriodView> listPayPeriods() {
        return ServiceGuard.call(log, "list pay periods", () -> {
            List<PayPeriodView> found = payPeriods.findAllByOrderByPeriodStartDesc().stream()
                    .map(PayrollQueryService::toView)
                    .toList();
            log.info("Found {} pay period(s)", found.size());
            return found;
        });
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
}
