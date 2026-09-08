package com.payroll.demo.domain;


import com.payroll.demo.hrms.HrmsFailureKind;

/**
 * Why a run ended in {@link IngestionStatus#FAILED}.
 *
 * <p>Mirrors {@link HrmsFailureKind} and adds the one cause that is ours rather than the
 * upstream provider's.
 */
public enum IngestionFailureKind {

    BAD_REQUEST,
    UNAUTHORIZED,
    NOT_FOUND,
    CONFLICT,
    CLIENT_ERROR,
    RATE_LIMITED,
    SERVER_ERROR,
    TIMEOUT,
    TRANSPORT,
    MALFORMED_RESPONSE,

    /** A bug or database fault on our side, not an HRMS problem. */
    INTERNAL_ERROR;

    /**
     * Translates an upstream failure kind.
     *
     * <p>Written as an exhaustive switch on purpose: adding a {@link HrmsFailureKind}
     * should fail the build here rather than silently record the wrong cause.
     */
    public static IngestionFailureKind from(HrmsFailureKind kind) {
        return switch (kind) {
            case BAD_REQUEST -> BAD_REQUEST;
            case UNAUTHORIZED -> UNAUTHORIZED;
            case NOT_FOUND -> NOT_FOUND;
            case CONFLICT -> CONFLICT;
            case CLIENT_ERROR -> CLIENT_ERROR;
            case RATE_LIMITED -> RATE_LIMITED;
            case SERVER_ERROR -> SERVER_ERROR;
            case TIMEOUT -> TIMEOUT;
            case TRANSPORT -> TRANSPORT;
            case MALFORMED_RESPONSE -> MALFORMED_RESPONSE;
        };
    }
}
