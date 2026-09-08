package com.payroll.demo.exception;

import org.springframework.http.HttpStatus;

import java.io.Serial;

/**
 * The one exception this application raises for anything a caller will see.
 *
 * <p>It replaces a class per situation. Six of those classes existed only to pair a message
 * with a status, which a factory method does just as well and without asking a reader to
 * open six files to learn four statuses.
 *
 * <p>{@link #badRequest} is the common case by far: almost everything that goes wrong with
 * a request is the request. The other three exist because collapsing them into 400 would
 * throw away something the caller needs:
 *
 * <ul>
 *   <li>{@link #notFound} — the request was well formed and simply named something that is
 *       not there. Telling the caller it was malformed sends them to re-check a URL that
 *       was correct.</li>
 *   <li>{@link #conflict} — nothing is wrong with the request; it collided with work
 *       already running, and the same call will succeed shortly.</li>
 *   <li>{@link #unavailable} — the fault is ours. A 400 here would blame the caller for an
 *       outage they cannot do anything about.</li>
 * </ul>
 *
 * <p>The only other exception in this package is {@link IngestionRecordException}, which is
 * an internal signal for a single bad record inside a feed and never becomes a response.
 */
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The request cannot be acted on: a missing field, an impossible range, a bad value. */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, null);
    }

    public static ApiException badRequest(String message, Throwable cause) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, cause);
    }

    /** The request named something that does not exist. */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, null);
    }

    /** The request collided with work already in progress; retrying later will succeed. */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message, null);
    }

    /** A dependency we own could not answer. */
    public static ApiException unavailable(String message, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Machine-readable code for the error body.
     *
     * <p>Derived from the status, so it stays in step with it automatically:
     * {@code BAD_REQUEST}, {@code NOT_FOUND}, {@code CONFLICT}, {@code SERVICE_UNAVAILABLE}.
     */
    public String getCode() {
        return status.name();
    }
}
