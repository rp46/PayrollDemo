package com.payroll.demo.controller;

import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayPeriodStatus;
import com.payroll.demo.dto.PayrollDtos.DepartmentView;
import com.payroll.demo.dto.PayrollDtos.EmployeeView;
import com.payroll.demo.dto.PayrollDtos.PayPeriodView;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.service.PayrollQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PayrollController.class)
class PayrollControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PayrollQueryService service;

    private static EmployeeView employee() {
        return new EmployeeView(7L, "E-1001", "Asha", "Menon", "asha@example.com",
                LocalDate.of(2023, 2, 13), EmployeeStatus.ACTIVE, "ENG", new BigDecimal("1450000.00"));
    }

    @Test
    void listsDepartments() throws Exception {
        when(service.listDepartments()).thenReturn(List.of(new DepartmentView(1L, "ENG", "Engineering")));

        mvc.perform(get("/api/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ENG"))
                .andExpect(jsonPath("$[0].name").value("Engineering"));
    }

    @Test
    @DisplayName("no status parameter passes null through, meaning everyone")
    void listsAllEmployees() throws Exception {
        when(service.listEmployees(isNull())).thenReturn(List.of(employee()));

        mvc.perform(get("/api/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeCode").value("E-1001"))
                .andExpect(jsonPath("$[0].baseSalary").value(1450000.00));

        verify(service).listEmployees(null);
    }

    @Test
    void bindsTheStatusFilter() throws Exception {
        when(service.listEmployees(any())).thenReturn(List.of());

        mvc.perform(get("/api/employees").param("status", "ON_LEAVE"))
                .andExpect(status().isOk());

        verify(service).listEmployees(EmployeeStatus.ON_LEAVE);
    }

    @Test
    void rejectsAnUnknownStatus() throws Exception {
        mvc.perform(get("/api/employees").param("status", "RETIRED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void findsOneEmployee() throws Exception {
        when(service.findEmployee("E-1001")).thenReturn(employee());

        mvc.perform(get("/api/employees/{code}", "E-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Asha"))
                .andExpect(jsonPath("$.departmentCode").value("ENG"));
    }

    @Test
    void unknownEmployeeIsNotFound() throws Exception {
        when(service.findEmployee("E-NOPE")).thenThrow(ApiException.notFound("No employee with code E-NOPE"));

        mvc.perform(get("/api/employees/{code}", "E-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void listsPayPeriods() throws Exception {
        when(service.listPayPeriods()).thenReturn(List.of(new PayPeriodView(3L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 1), PayPeriodStatus.PAID)));

        mvc.perform(get("/api/pay-periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].status").value("PAID"));
    }

    @Test
    @DisplayName("an empty result is an empty array, not a 404")
    void emptyListsAreOk() throws Exception {
        when(service.listDepartments()).thenReturn(List.of());

        mvc.perform(get("/api/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
