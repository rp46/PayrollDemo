package com.payroll.demo.controller;

import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.dto.BulkUpsertResult;
import com.payroll.demo.dto.BulkWriteCounts;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.service.BulkPayrollService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller does one thing, so this checks exactly that: request in, service called
 * with the right arguments, service result out. No transform or persistence logic here.
 */
@WebMvcTest(BulkPayrollController.class)
class BulkPayrollControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BulkPayrollService service;

    private static BulkUpsertResult okResult() {
        return new BulkUpsertResult(7L, IngestionStatus.SUCCEEDED, true,
                3, 2, 0, 2,
                new BulkWriteCounts(1, 3, 1, 2, 5),
                null, null, List.of());
    }

    @Test
    @DisplayName("the request body is passed straight through to the service")
    void passesRequestThrough() throws Exception {
        when(service.upsertAll(any(), any(), any(), any(), anyBoolean())).thenReturn(okResult());

        mvc.perform(post("/api/payroll/bulk-upsert")
                        .contentType("application/json")
                        .content("""
                                {"periodStart":"2026-08-01","periodEnd":"2026-08-31",
                                 "updatedSince":"2026-07-01","strict":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(7))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.written.payslips").value(2));

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> since = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Boolean> strict = ArgumentCaptor.forClass(Boolean.class);
        verify(service).upsertAll(eq(IngestionTrigger.MANUAL),
                start.capture(), end.capture(), since.capture(), strict.capture());

        assertThat(start.getValue()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(end.getValue()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(since.getValue()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(strict.getValue()).isTrue();
    }

    @Test
    @DisplayName("an empty body is valid and defaults strict to false")
    void emptyBodyDefaults() throws Exception {
        when(service.upsertAll(any(), any(), any(), any(), anyBoolean())).thenReturn(okResult());

        mvc.perform(post("/api/payroll/bulk-upsert").contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        verify(service).upsertAll(IngestionTrigger.MANUAL, null, null, null, false);
    }

    @Test
    @DisplayName("no body at all is accepted too")
    void noBodyAccepted() throws Exception {
        when(service.upsertAll(any(), any(), any(), any(), anyBoolean())).thenReturn(okResult());

        mvc.perform(post("/api/payroll/bulk-upsert")).andExpect(status().isOk());

        verify(service).upsertAll(IngestionTrigger.MANUAL, null, null, null, false);
    }

    @Test
    @DisplayName("a rolled-back run is still 200: the failure is in the body, not the status")
    void rollbackIsReportedInTheBody() throws Exception {
        when(service.upsertAll(any(), any(), any(), any(), anyBoolean())).thenReturn(
                new BulkUpsertResult(8L, IngestionStatus.FAILED, false, 3, 0, 0, 1, null,
                        IngestionFailureKind.INTERNAL_ERROR,
                        "batch rolled back: employees_base_salary_chk", List.of()));

        mvc.perform(post("/api/payroll/bulk-upsert").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureDetail").value(
                        org.hamcrest.Matchers.containsString("rolled back")));
    }

    @Test
    @DisplayName("a concurrent run is refused with 409")
    void concurrentRunReturnsConflict() throws Exception {
        when(service.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(ApiException.conflict("A bulk payroll upsert is already in progress; wait for it to finish before starting another."));

        mvc.perform(post("/api/payroll/bulk-upsert").contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }
}
