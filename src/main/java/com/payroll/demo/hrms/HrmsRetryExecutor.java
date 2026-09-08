package com.payroll.demo.hrms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs an HRMS call, retrying only the failures worth retrying.
 *
 * <p>Delays grow exponentially and carry jitter so that a fleet of instances recovering
 * from the same upstream outage does not re-converge into a synchronised thundering herd.
 * A {@code Retry-After} from the server wins over the computed delay, capped so a hostile
 * or mistaken value cannot pin the thread indefinitely.
 *
 * <p>Non-retryable failures (400, 401, 404, unparseable bodies) propagate on the first
 * attempt — there is nothing to wait for.
 */
@Component
public class HrmsRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(HrmsRetryExecutor.class);

    private final HrmsProperties.Retry config;
    private final Sleeper sleeper;

    @Autowired
    public HrmsRetryExecutor(HrmsProperties properties) {
        this(properties.getRetry(), duration -> Thread.sleep(duration.toMillis()));
    }

    /** Test seam: lets a suite exercise the backoff schedule without real waiting. */
    public HrmsRetryExecutor(HrmsProperties.Retry config, Sleeper sleeper) {
        this.config = config;
        this.sleeper = sleeper;
    }

    /**
     * Invokes {@code call}, retrying retryable {@link HrmsApiException}s per the policy.
     *
     * @param description short label used in logs, e.g. {@code "GET /workers page 3"}
     * @throws HrmsApiException the last failure, once attempts are exhausted or the
     *                          failure is not retryable
     */
    public <T> T execute(String description, HrmsCall<T> call) {
        int maxAttempts = Math.max(1, config.getMaxAttempts());
        HrmsApiException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.run();
            } catch (HrmsApiException ex) {
                last = ex;

                if (!ex.isRetryable()) {
                    log.warn("{} failed permanently on attempt {}/{}: {}",
                            description, attempt, maxAttempts, ex.describe());
                    throw ex;
                }
                if (attempt == maxAttempts) {
                    log.error("{} still failing after {} attempt(s), giving up: {}",
                            description, maxAttempts, ex.describe());
                    throw ex;
                }

                Duration delay = delayFor(attempt, ex.getRetryAfter());
                log.warn("{} failed on attempt {}/{} ({}), retrying in {} ms",
                        description, attempt, maxAttempts, ex.describe(), delay.toMillis());
                if (!pause(delay)) {
                    // Interrupted: stop retrying and surface the failure we already have.
                    throw ex;
                }
            }
        }

        // Unreachable: the loop either returns or throws.
        throw last != null ? last
                : new HrmsApiException(HrmsFailureKind.TRANSPORT, description + " produced no result");
    }

    /**
     * Delay before the attempt following {@code completedAttempt}.
     *
     * <p>Visible for testing.
     */
    Duration delayFor(int completedAttempt, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) {
            Duration capped = min(retryAfter, config.getMaxRetryAfter());
            return capped.isZero() ? Duration.ZERO : capped;
        }

        double base = config.getInitialBackoff().toMillis()
                * Math.pow(Math.max(1.0, config.getMultiplier()), completedAttempt - 1.0);
        double capped = Math.min(base, config.getMaxBackoff().toMillis());

        double jitter = Math.max(0.0, config.getJitter());
        if (jitter > 0) {
            // Symmetric jitter: capped * (1 ± jitter).
            double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
            capped = capped * factor;
        }
        return Duration.ofMillis(Math.max(0L, Math.round(capped)));
    }

    /** @return {@code false} if the wait was interrupted, with the interrupt flag restored. */
    private boolean pause(Duration delay) {
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Retry wait interrupted; abandoning remaining attempts");
            return false;
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** A single HRMS call attempt. */
    @FunctionalInterface
    public interface HrmsCall<T> {
        T run() throws HrmsApiException;
    }

    /** Indirection over {@link Thread#sleep} so tests need not actually wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }
}
