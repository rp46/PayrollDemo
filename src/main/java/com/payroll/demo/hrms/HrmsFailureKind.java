package com.payroll.demo.hrms;

/**
 * How a call to the HRMS API failed, and whether retrying it could plausibly help.
 *
 * <p>The split matters: retrying a 400 just burns the same request against the same
 * rejection, while not retrying a 503 throws away data the upstream would have
 * served a second later.
 */
public enum HrmsFailureKind {

    /** 400/422 — the request itself is wrong. Retrying re-sends the same bad request. */
    BAD_REQUEST(false),

    /** 401/403 — credentials missing, expired or insufficient. Needs operator action. */
    UNAUTHORIZED(false),

    /** 404 — endpoint or resource does not exist for this tenant. */
    NOT_FOUND(false),

    /** 409 — upstream state conflict; re-sending will conflict identically. */
    CONFLICT(false),

    /** Any other non-retryable 4xx. */
    CLIENT_ERROR(false),

    /** 429 — throttled. Retryable, and the {@code Retry-After} hint is honoured. */
    RATE_LIMITED(true),

    /** 500/502/503/504 — upstream fault, usually transient. */
    SERVER_ERROR(true),

    /** Connect or read timeout. */
    TIMEOUT(true),

    /** Connection refused, DNS failure, reset socket. */
    TRANSPORT(true),

    /** 2xx with a body we could not parse — a contract break, not a blip. */
    MALFORMED_RESPONSE(false);

    private final boolean retryable;

    HrmsFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
