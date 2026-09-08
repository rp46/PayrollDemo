package com.payroll.demo.controller;

import com.payroll.demo.dto.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handler branches no current route can reach.
 *
 * <p>Every endpoint takes optional parameters and only one takes a body, so a missing
 * parameter or an unreadable payload cannot be provoked through MockMvc here. They are
 * still reachable the moment an endpoint is added, so the handling is exercised directly
 * rather than left untested.
 */
class GlobalExceptionHandlerUnitTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/employees");
        request.setRequestURI("/api/employees");
        return request;
    }

    @Test
    @DisplayName("a missing required parameter names the parameter")
    void missingParameterNamesIt() {
        var ex = new MissingServletRequestParameterException("employeeCode", "String");

        ResponseEntity<ApiError> response = handler.handleMissingParameter(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().message()).contains("employeeCode").contains("missing");
        assertThat(response.getBody().path()).isEqualTo("/api/employees");
    }

    @Test
    @DisplayName("an unreadable body says so without quoting the payload back")
    void unreadableBodyDoesNotEchoThePayload() {
        var ex = new HttpMessageNotReadableException(
                "Unexpected character in {\"secret\":\"do-not-echo\"}",
                new MockHttpInputMessage());

        ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("The request body could not be parsed as JSON.");
        assertThat(response.getBody().message())
                .as("the parser message can quote the payload, so it is logged and not returned")
                .doesNotContain("do-not-echo");
    }

    @Test
    @DisplayName("a type mismatch with no declared target type still produces a usable message")
    void typeMismatchWithoutARequiredType() {
        var ex = new MethodArgumentTypeMismatchException(
                "nonsense", null, "from", (MethodParameter) null, new IllegalArgumentException("bad"));

        ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .contains("'from'")
                .contains("nonsense")
                .contains("the expected type");
    }

    @Test
    void typeMismatchNamesTheExpectedType() {
        var ex = new MethodArgumentTypeMismatchException(
                "nonsense", LocalDate.class, "from", (MethodParameter) null,
                new IllegalArgumentException("bad"));

        ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex, request());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("expected LocalDate");
    }

    @Test
    @DisplayName("a 405 with no declared alternatives still returns a sentence, not a null join")
    void methodNotAllowedWithoutSupportedMethods() {
        var ex = new HttpRequestMethodNotSupportedException("PATCH");

        ResponseEntity<ApiError> response = handler.handleWrongMethod(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .contains("PATCH")
                .contains("a supported method");
    }

    @Test
    void methodNotAllowedListsTheSupportedVerbs() {
        var ex = new HttpRequestMethodNotSupportedException("PATCH", java.util.List.of("GET", "HEAD"));

        ResponseEntity<ApiError> response = handler.handleWrongMethod(ex, request());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("GET, HEAD");
    }

    /** Minimal stand-in; {@link HttpMessageNotReadableException} requires an input message. */
    private static final class MockHttpInputMessage
            implements org.springframework.http.HttpInputMessage {

        @Override
        public java.io.InputStream getBody() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return new org.springframework.http.HttpHeaders();
        }
    }
}
