package com.payroll.demo.service;

import com.payroll.demo.domain.Department;
import com.payroll.demo.domain.Employee;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayPeriod;
import com.payroll.demo.domain.PayPeriodStatus;
import com.payroll.demo.dto.PayrollDtos.DepartmentView;
import com.payroll.demo.dto.PayrollDtos.EmployeeView;
import com.payroll.demo.dto.PayrollDtos.PayPeriodView;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.repository.DepartmentRepository;
import com.payroll.demo.repository.EmployeeRepository;
import com.payroll.demo.repository.PayPeriodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollQueryServiceTest {

    private DepartmentRepository departments;
    private EmployeeRepository employees;
    private PayPeriodRepository payPeriods;
    private PayrollQueryService service;

    @BeforeEach
    void setUp() {
        departments = mock(DepartmentRepository.class);
        employees = mock(EmployeeRepository.class);
        payPeriods = mock(PayPeriodRepository.class);
        service = new PayrollQueryService(departments, employees, payPeriods);
    }

    private static Department department(String code, String name) {
        Department d = new Department();
        d.setId(1L);
        d.setCode(code);
        d.setName(name);
        return d;
    }

    private static Employee employee(String code, EmployeeStatus status, Department dept) {
        Employee e = new Employee();
        e.setId(7L);
        e.setEmployeeCode(code);
        e.setFirstName("Asha");
        e.setLastName("Menon");
        e.setEmail(code.toLowerCase() + "@example.com");
        e.setHireDate(LocalDate.of(2023, 2, 13));
        e.setStatus(status);
        e.setDepartment(dept);
        e.setBaseSalary(new BigDecimal("1450000.00"));
        return e;
    }

    // ----------------------------------------------------------- departments

    @Test
    void listsDepartments() {
        when(departments.findAll()).thenReturn(List.of(department("ENG", "Engineering")));

        List<DepartmentView> found = service.listDepartments();

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("ENG");
            assertThat(d.name()).isEqualTo("Engineering");
        });
    }

    @Test
    void emptyDepartmentListIsNotAnError() {
        when(departments.findAll()).thenReturn(List.of());
        assertThat(service.listDepartments()).isEmpty();
    }

    // ------------------------------------------------------------- employees

    @Test
    @DisplayName("no status filter lists everyone, ordered by the repository")
    void listsAllEmployeesWhenNoStatusGiven() {
        when(employees.findAllByOrderByEmployeeCodeAsc())
                .thenReturn(List.of(employee("E-1001", EmployeeStatus.ACTIVE, department("ENG", "Engineering"))));

        List<EmployeeView> found = service.listEmployees(null);

        assertThat(found).singleElement().satisfies(e -> {
            assertThat(e.employeeCode()).isEqualTo("E-1001");
            assertThat(e.departmentCode()).isEqualTo("ENG");
            assertThat(e.baseSalary()).isEqualByComparingTo("1450000.00");
        });
        verify(employees, never()).findByStatusOrderByEmployeeCodeAsc(any());
    }

    @Test
    @DisplayName("a status filter uses the narrower query instead")
    void listsEmployeesByStatus() {
        when(employees.findByStatusOrderByEmployeeCodeAsc(EmployeeStatus.ON_LEAVE))
                .thenReturn(List.of(employee("E-1004", EmployeeStatus.ON_LEAVE, null)));

        List<EmployeeView> found = service.listEmployees(EmployeeStatus.ON_LEAVE);

        assertThat(found).singleElement()
                .satisfies(e -> assertThat(e.status()).isEqualTo(EmployeeStatus.ON_LEAVE));
        verify(employees, never()).findAllByOrderByEmployeeCodeAsc();
    }

    @Test
    @DisplayName("an employee with no department maps to a null code, not a crash")
    void employeeWithoutDepartment() {
        when(employees.findAllByOrderByEmployeeCodeAsc())
                .thenReturn(List.of(employee("E-1001", EmployeeStatus.ACTIVE, null)));

        assertThat(service.listEmployees(null).getFirst().departmentCode()).isNull();
    }

    @Test
    void findsAnEmployeeByCode() {
        when(employees.findByEmployeeCode("E-1001"))
                .thenReturn(Optional.of(employee("E-1001", EmployeeStatus.ACTIVE, department("ENG", "Engineering"))));

        assertThat(service.findEmployee("E-1001").employeeCode()).isEqualTo("E-1001");
    }

    @Test
    @DisplayName("the code is trimmed before it is looked up")
    void trimsTheEmployeeCode() {
        when(employees.findByEmployeeCode("E-1001"))
                .thenReturn(Optional.of(employee("E-1001", EmployeeStatus.ACTIVE, null)));

        assertThat(service.findEmployee("  E-1001  ").employeeCode()).isEqualTo("E-1001");
    }

    @Test
    void unknownEmployeeIsNotFound() {
        when(employees.findByEmployeeCode("E-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findEmployee("E-NOPE"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No employee with code E-NOPE")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a blank or oversized code is refused before any query runs")
    void malformedCodeIsRejected() {
        assertThatThrownBy(() -> service.findEmployee(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> service.findEmployee("   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("employeeCode is required");

        assertThatThrownBy(() -> service.findEmployee("X".repeat(21)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at most 20 characters");

        verify(employees, never()).findByEmployeeCode(any());
    }

    // ----------------------------------------------------------- pay periods

    @Test
    void listsPayPeriods() {
        PayPeriod period = new PayPeriod();
        period.setId(3L);
        period.setPeriodStart(LocalDate.of(2026, 8, 1));
        period.setPeriodEnd(LocalDate.of(2026, 8, 31));
        period.setPayDate(LocalDate.of(2026, 9, 1));
        period.setStatus(PayPeriodStatus.PAID);
        when(payPeriods.findAllByOrderByPeriodStartDesc()).thenReturn(List.of(period));

        List<PayPeriodView> found = service.listPayPeriods();

        assertThat(found).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(3L);
            assertThat(p.payDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(p.status()).isEqualTo(PayPeriodStatus.PAID);
        });
    }

    // ------------------------------------------------------ failure handling

    @Test
    @DisplayName("a database failure surfaces as 503, not as the raw Spring exception")
    void databaseFailureBecomesServiceUnavailable() {
        when(departments.findAll()).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> service.listDepartments())
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a null pointer inside a query does not escape as a 5xx")
    void nullPointerBecomesBadRequest() {
        when(employees.findAllByOrderByEmployeeCodeAsc()).thenThrow(new NullPointerException("boom"));

        assertThatThrownBy(() -> service.listEmployees(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
