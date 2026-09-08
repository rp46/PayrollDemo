package com.payroll.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * The single error shape every failed request returns.
 *
 * <p>One shape for all of them means a client can parse a failure without knowing which
 * endpoint produced it.
 *
 * @param error a stable, machine-readable code; clients should branch on this, not on the
 *              wording of {@code message}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path);
    }
}
