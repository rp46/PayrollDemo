package com.payroll.demo.controller;

import com.payroll.demo.domain.PayslipStatus;
import com.payroll.demo.exception.ApiException;
import com.payroll.demo.service.PayslipQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No request should ever produce an unhandled 5xx or a stack trace on the wire.
 */
@WebMvcTest(PayslipController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PayslipQueryService service;

    @Test
    @DisplayName("every failure uses the same body shape, coded by status")
    void errorBodyIsConsistent() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any()))
                .thenThrow(ApiException.notFound("No employee with code " + "E-NOPE"));

        mvc.perform(get("/api/employees/{code}/payslips", "E-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No employee with code E-NOPE"))
                .andExpect(jsonPath("$.path").value("/api/employees/E-NOPE/payslips"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("a bad argument from the service is a 400, not a 500")
    void invalidRequestIsBadRequest() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any()))
                .thenThrow(ApiException.badRequest("'to' must not be before 'from'"));

        mvc.perform(get("/api/employees/{code}/payslips", "E-1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("'to' must not be before 'from'"));
    }

    @Test
    @DisplayName("a database outage is 503, since the request itself was fine")
    void databaseOutageIsServiceUnavailable() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any()))
                .thenThrow(ApiException.unavailable(
                        "The payroll database is not reachable right now; list payslips could not be "
                                + "completed. Please retry shortly.",
                        new DataIntegrityViolationException("connection reset")));

        mvc.perform(get("/api/employees/{code}/payslips", "E-1001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("retry shortly")));
    }

    @Test
    @DisplayName("an unparseable query parameter names the parameter at fault")
    void typeMismatchNamesTheParameter() throws Exception {
        mvc.perform(get("/api/employees/{code}/payslips", "E-1001").param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("'from' has an invalid value 'yesterday'")));
    }

    @Test
    void nonNumericPathVariableIsBadRequest() throws Exception {
        mvc.perform(get("/api/payslips/{id}", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a null pointer escaping the service is still not a 5xx")
    void nullPointerIsBadRequest() throws Exception {
        when(service.latestPayslipFor(any())).thenThrow(new NullPointerException("boom"));

        mvc.perform(get("/api/employees/{code}/payslips/latest", "E-1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                // The JVM message is logged, never returned.
                .andExpect(jsonPath("$.message").value("The request could not be processed."));
    }

    @Test
    void illegalArgumentIsBadRequest() throws Exception {
        when(service.latestPayslipFor(any())).thenThrow(new IllegalArgumentException("bad arg"));

        mvc.perform(get("/api/employees/{code}/payslips/latest", "E-1001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the wrong verb is 405, with the supported ones named")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(post("/api/employees/{code}/payslips", "E-1001"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("an unknown status value is rejected without exposing the enum internals")
    void unknownEnumValueIsBadRequest() throws Exception {
        mvc.perform(get("/api/employees/{code}/payslips", "E-1001").param("status", "SHREDDED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a valid request is untouched by any of this")
    void successfulRequestIsUnaffected() throws Exception {
        when(service.listPayslipsFor(any(), any(), any(), any())).thenReturn(java.util.List.of());

        mvc.perform(get("/api/employees/{code}/payslips", "E-1001")
                        .param("status", PayslipStatus.PAID.name()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unrecognised failure stays a 500, and leaks nothing")
    void trulyUnexpectedFailureIsInternalServerError() throws Exception {
        when(service.latestPayslipFor(any()))
                .thenThrow(new UnsupportedOperationException("internal detail nobody outside should see"));

        mvc.perform(get("/api/employees/{code}/payslips/latest", "E-1001"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("has been logged")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("internal detail"))));
    }

    @Test
    @DisplayName("a 5xx raised deliberately is logged as an error but still returns a clean body")
    void deliberateServerSideFailureIsReported() throws Exception {
        when(service.latestPayslipFor(any()))
                .thenThrow(ApiException.unavailable("database is down", new RuntimeException("pool exhausted")));

        mvc.perform(get("/api/employees/{code}/payslips/latest", "E-1001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("database is down"));
    }

    @Test
    @DisplayName("an unknown path is a 404, not a stack trace")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/no-such-endpoint"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the path that failed is echoed back, query string aside")
    void errorBodyNamesThePath() throws Exception {
        when(service.findPayslip(any())).thenThrow(ApiException.notFound("No payslip with id 5"));

        mvc.perform(get("/api/payslips/{id}", 5))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/api/payslips/5"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
