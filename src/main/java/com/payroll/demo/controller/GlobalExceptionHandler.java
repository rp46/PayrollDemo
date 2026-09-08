package com.payroll.demo.controller;

import com.payroll.demo.dto.ApiError;
import com.payroll.demo.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception that reaches the web layer into an {@link ApiError}.
 *
 * <p>The service tier already normalises its failures (see {@code ServiceGuard}), so most
 * of what arrives here is an {@link ApiException} that knows its own status. The rest of
 * these handlers cover the failures Spring raises before a controller method is ever
 * entered — an unparseable date in the query string, a missing parameter, the wrong verb —
 * which no amount of service-layer care can prevent.
 *
 * <p>A malformed request is a 400 with a message naming the offending parameter, never a
 * 500 and never a stack trace on the wire.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Anything the application raised deliberately; the exception carries its own status. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus();

        if (status.is5xxServerError()) {
            log.error("{} {} -> {}: {}", request.getMethod(), request.getRequestURI(),
                    status.value(), ex.getMessage(), ex);
        } else {
            log.warn("{} {} -> {}: {}", request.getMethod(), request.getRequestURI(),
                    status.value(), ex.getMessage());
        }
        return build(status, ex.getMessage(), request);
    }

    /** A path variable or query parameter that could not be converted, e.g. {@code ?from=nonsense}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "the expected type"
                : ex.getRequiredType().getSimpleName();
        String message = "'" + ex.getName() + "' has an invalid value '" + ex.getValue()
                + "'; expected " + expected + ".";

        log.warn("{} {} -> 400: {}", request.getMethod(), request.getRequestURI(), message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                          HttpServletRequest request) {
        String message = "Required parameter '" + ex.getParameterName() + "' is missing.";
        log.warn("{} {} -> 400: {}", request.getMethod(), request.getRequestURI(), message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                         HttpServletRequest request) {
        // The parser message can quote the payload, so it is logged but not returned.
        log.warn("{} {} -> 400: unreadable request body: {}",
                request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.BAD_REQUEST,
                "The request body could not be parsed as JSON.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleWrongMethod(HttpRequestMethodNotSupportedException ex,
                                                      HttpServletRequest request) {
        String message = ex.getMethod() + " is not supported here; use "
                + String.join(", ", ex.getSupportedMethods() == null
                ? new String[]{"a supported method"} : ex.getSupportedMethods()) + ".";
        log.warn("{} {} -> 405", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.METHOD_NOT_ALLOWED, message, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                     HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "No endpoint at this path.", request);
    }

    /**
     * The bad-argument family, if one ever reaches here.
     *
     * <p>{@code ServiceGuard} converts these inside the service tier, so arriving here means
     * the fault is in the web layer itself. Still a 400 rather than a 500, per the same
     * reasoning, and still logged in full so the defect is visible.
     */
    @ExceptionHandler({NullPointerException.class, IllegalArgumentException.class,
            IllegalStateException.class, ClassCastException.class,
            IndexOutOfBoundsException.class, ArithmeticException.class})
    public ResponseEntity<ApiError> handleBadArgument(RuntimeException ex, HttpServletRequest request) {
        log.error("{} {} -> 400: {} escaped the service tier and was not converted",
                request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName(), ex);
        return build(HttpStatus.BAD_REQUEST,
                "The request could not be processed.", request);
    }

    /**
     * The genuine last resort.
     *
     * <p>Left as a 500 on purpose: it means something failed that this application does not
     * understand, and calling that a client error would send whoever is reading it to look
     * in the wrong place. The body carries no internals; the log carries everything.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("{} {} -> 500: unhandled {}",
                request.getMethod(), request.getRequestURI(), ex.getClass().getName(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be completed. The failure has been logged.", request);
    }


    /**
     * Builds the response, taking the error code from the status.
     *
     * <p>One rule for the whole API, so a code can never drift from the status beside it.
     * Which parameter was wrong, and why, is the job of {@code message}.
     */
    private static ResponseEntity<ApiError> build(HttpStatus status, String message,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.name(), message, request.getRequestURI()));
    }
}
