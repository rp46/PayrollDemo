package com.payroll.demo.hrms;

import java.io.Serial;
import java.time.Duration;

/**
 * A failed HRMS API call, classified by {@link HrmsFailureKind}.
 *
 * <p>Carries the upstream status and a truncated slice of the response body, which is
 * what actually makes a 400 debuggable — the HRMS explains the rejection in the body,
 * not the status line.
 */
public class HrmsApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Response bodies are kept for diagnosis but never unbounded. */
    public static final int MAX_BODY_SNIPPET = 1000;

    private final HrmsFailureKind kind;
    private final Integer statusCode;
    private final String responseBody;
    private final Duration retryAfter;

    public HrmsApiException(HrmsFailureKind kind, String message) {
        this(kind, message, null, null, null, null);
    }

    public HrmsApiException(HrmsFailureKind kind, String message, Throwable cause) {
        this(kind, message, null, null, null, cause);
    }

    public HrmsApiException(HrmsFailureKind kind,
                            String message,
                            Integer statusCode,
                            String responseBody,
                            Duration retryAfter,
                            Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
        this.responseBody = truncate(responseBody);
        this.retryAfter = retryAfter;
    }

    public HrmsFailureKind getKind() {
        return kind;
    }

    /** HTTP status, or {@code null} for transport-level failures that never got one. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** Truncated response body, or {@code null} if there was none. */
    public String getResponseBody() {
        return responseBody;
    }

    /** Server-supplied {@code Retry-After}, or {@code null} if absent/unparseable. */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean isRetryable() {
        return kind.isRetryable();
    }

    /** One-line summary safe for logs and for the {@code failure_detail} column. */
    public String describe() {
        StringBuilder sb = new StringBuilder(kind.name());
        if (statusCode != null) {
            sb.append(" (HTTP ").append(statusCode).append(')');
        }
        sb.append(": ").append(getMessage());
        if (responseBody != null && !responseBody.isBlank()) {
            sb.append(" | body=").append(responseBody);
        }
        return sb.toString();
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        // "\\s+" is the regex for any whitespace. Note the escaping: the bare "\s" is a
        // Java string escape for a single space, which compiles happily but leaves
        // newlines and tabs in place — exactly what this is meant to remove.
        String collapsed = body.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= MAX_BODY_SNIPPET
                ? collapsed
                : collapsed.substring(0, MAX_BODY_SNIPPET) + "…[truncated]";
    }
}
