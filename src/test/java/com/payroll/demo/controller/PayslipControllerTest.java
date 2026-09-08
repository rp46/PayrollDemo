package com.payroll.demo.controller;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.dto.PayrollDtos.ComponentTotalView;
import com.payroll.demo.dto.PayrollDtos.PayDetailsView;
import com.payroll.demo.dto.PayrollDtos.PaySummaryView;
import com.payroll.demo.dto.PayrollDtos.PayslipLineView;
import com.payroll.demo.dto.PayrollDtos.PayslipView;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.service.PayslipQueryService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request binding and status mapping for the payslip endpoints. The query logic itself is
 * covered against a real database in {@code PayslipQueryServiceTest}.
 */
@WebMvcTest(PayslipController.class)
class PayslipControllerTest {

    private static final String EMP = "E-1001";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PayslipQueryService service;

    private static PayslipView payslip() {
        return new PayslipView(7L, EMP,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1),
                new BigDecimal("100000.00"), new BigDecimal("18000.00"), new BigDecimal("82000.00"),
                PayslipStatus.PAID,
                List.of(new PayslipLineView("BASIC", ComponentType.EARNING, "Basic pay",
                        new BigDecimal("100000.00"))));
    }

    private static PayDetailsView payDetails() {
        return new PayDetailsView(EMP, "Asha", "Menon", EmployeeStatus.ACTIVE, "ENG",
                LocalDate.of(2023, 2, 13), null, new BigDecimal("1450000.00"), null,
                null, null, null,
                new PaySummaryView(2, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31),
                        new BigDecimal("200000.00"), new BigDecimal("36000.00"),
                        new BigDecimal("164000.00"), new BigDecimal("82000.00")),
                List.of(new ComponentTotalView("BASIC", ComponentType.EARNING,
                        new BigDecimal("200000.00"), 2)),
                payslip());
    }

    // --------------------------------------------------------------- listing

    @Test
    @DisplayName("an unfiltered listing passes nulls through for every filter")
    void listsWithoutFilters() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any())).thenReturn(List.of(payslip()));

        mvc.perform(get("/api/employees/{code}/payslips", EMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].employeeCode").value(EMP))
                .andExpect(jsonPath("$[0].payDate").value("2026-09-01"))
                .andExpect(jsonPath("$[0].lines[0].componentCode").value("BASIC"));

        verify(service).listPayslipsFor(EMP, null, null, null);
    }

    @Test
    @DisplayName("ISO dates and the status enum bind from the query string")
    void bindsFilters() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/employees/{code}/payslips", EMP)
                        .param("from", "2026-04-01")
                        .param("to", "2027-03-31")
                        .param("status", "PAID"))
                .andExpect(status().isOk());

        verify(service).listPayslipsFor(EMP,
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), PayslipStatus.PAID);
    }

    @Test
    @DisplayName("an unparseable date is a 400, not a 500")
    void rejectsMalformedDate() throws Exception {
        mvc.perform(get("/api/employees/{code}/payslips", EMP).param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownStatusValue() throws Exception {
        mvc.perform(get("/api/employees/{code}/payslips", EMP).param("status", "SHREDDED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown employee surfaces as 404")
    void unknownEmployeeIs404() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any()))
                .thenThrow(ApiException.notFound("No employee with code " + "E-NOPE"));

        mvc.perform(get("/api/employees/{code}/payslips", "E-NOPE"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- single

    @Test
    void returnsTheLatestPayslip() throws Exception {
        when(service.latestPayslipFor(EMP)).thenReturn(payslip());

        mvc.perform(get("/api/employees/{code}/payslips/latest", EMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.netPay").value(82000.00));
    }

    @Test
    @DisplayName("latest is routed as its own endpoint, not as a payslip id")
    void latestDoesNotCollideWithTheListing() throws Exception {
        when(service.latestPayslipFor(EMP)).thenReturn(payslip());

        mvc.perform(get("/api/employees/{code}/payslips/latest", EMP)).andExpect(status().isOk());

        verify(service).latestPayslipFor(EMP);
        verify(service, org.mockito.Mockito.never()).listPayslipsFor(any(), any(), any(), any());
    }

    @Test
    void noPayslipsYieldsNotFound() throws Exception {
        when(service.latestPayslipFor(EMP)).thenThrow(ApiException.notFound("none"));

        mvc.perform(get("/api/employees/{code}/payslips/latest", EMP))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsAPayslipById() throws Exception {
        when(service.findPayslip(7L)).thenReturn(payslip());

        mvc.perform(get("/api/payslips/{id}", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void unknownPayslipIs404() throws Exception {
        when(service.findPayslip(99L)).thenThrow(ApiException.notFound("nope"));

        mvc.perform(get("/api/payslips/{id}", 99)).andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------- register

    @Test
    void returnsThePeriodRegister() throws Exception {
        when(service.listPayslipsForPeriod(3L)).thenReturn(List.of(payslip()));

        mvc.perform(get("/api/pay-periods/{id}/payslips", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeCode").value(EMP));
    }

    @Test
    void unknownPeriodIs404() throws Exception {
        when(service.listPayslipsForPeriod(99L)).thenThrow(ApiException.notFound("No pay period with id " + 99L));

        mvc.perform(get("/api/pay-periods/{id}/payslips", 99)).andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------- pay details

    @Test
    @DisplayName("pay details expose the summary, the breakdown and the latest payslip")
    void returnsPayDetails() throws Exception {
        when(service.payDetailsFor(any(), any(), any(), any())).thenReturn(payDetails());

        mvc.perform(get("/api/employees/{code}/pay-details", EMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value(EMP))
                .andExpect(jsonPath("$.baseSalary").value(1450000.00))
                .andExpect(jsonPath("$.summary.payslipCount").value(2))
                .andExpect(jsonPath("$.summary.totalNet").value(164000.00))
                .andExpect(jsonPath("$.summary.averageNetPerPayslip").value(82000.00))
                .andExpect(jsonPath("$.componentTotals[0].componentCode").value("BASIC"))
                .andExpect(jsonPath("$.componentTotals[0].occurrences").value(2))
                .andExpect(jsonPath("$.latestPayslip.id").value(7));

        verify(service).payDetailsFor(EMP, null, null, null);
    }

    @Test
    @DisplayName("pay details take the same filters as the listing")
    void payDetailsBindsFilters() throws Exception {
        when(service.payDetailsFor(any(), any(), any(), any())).thenReturn(payDetails());

        mvc.perform(get("/api/employees/{code}/pay-details", EMP)
                        .param("from", "2026-04-01")
                        .param("status", "PAID"))
                .andExpect(status().isOk());

        verify(service).payDetailsFor(eq(EMP), eq(LocalDate.of(2026, 4, 1)), isNull(), eq(PayslipStatus.PAID));
    }

    @Test
    void payDetailsForUnknownEmployeeIs404() throws Exception {
        when(service.payDetailsFor(any(), any(), any(), any()))
                .thenThrow(ApiException.notFound("No employee with code " + "E-NOPE"));

        mvc.perform(get("/api/employees/{code}/pay-details", "E-NOPE"))
                .andExpect(status().isNotFound());
    }
}
