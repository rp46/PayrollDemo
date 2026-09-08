package com.payroll.demo.hrms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry policy, exercised without any real waiting: the {@code Sleeper} seam records
 * the delays instead of sleeping them, so the schedule itself can be asserted.
 */
class HrmsRetryExecutorTest {

    private final List<Duration> slept = new ArrayList<>();

    private HrmsProperties.Retry policy() {
        HrmsProperties.Retry retry = new HrmsProperties.Retry();
        retry.setMaxAttempts(4);
        retry.setInitialBackoff(Duration.ofMillis(100));
        retry.setMaxBackoff(Duration.ofMillis(250));
        retry.setMultiplier(2.0);
        retry.setJitter(0.0);
        retry.setMaxRetryAfter(Duration.ofSeconds(60));
        return retry;
    }

    private HrmsRetryExecutor executorWith(HrmsProperties.Retry retry) {
        return new HrmsRetryExecutor(retry, slept::add);
    }

    @Test
    @DisplayName("a 400 is not retried: the same request would be rejected the same way")
    void badRequestFailsOnFirstAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        HrmsRetryExecutor executor = executorWith(policy());

        assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
            attempts.incrementAndGet();
            throw new HrmsApiException(HrmsFailureKind.BAD_REQUEST, "bad period filter",
                    400, "{\"error\":\"periodStart must precede periodEnd\"}", null, null);
        }))
                .isInstanceOf(HrmsApiException.class)
                .extracting(ex -> ((HrmsApiException) ex).getKind())
                .isEqualTo(HrmsFailureKind.BAD_REQUEST);

        assertThat(attempts).hasValue(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("401 and malformed bodies are equally non-retryable")
    void otherPermanentFailuresAreNotRetried() {
        for (HrmsFailureKind kind : List.of(HrmsFailureKind.UNAUTHORIZED, HrmsFailureKind.NOT_FOUND,
                HrmsFailureKind.CONFLICT, HrmsFailureKind.CLIENT_ERROR, HrmsFailureKind.MALFORMED_RESPONSE)) {
            AtomicInteger attempts = new AtomicInteger();
            HrmsRetryExecutor executor = executorWith(policy());

            assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
                attempts.incrementAndGet();
                throw new HrmsApiException(kind, "permanent");
            })).isInstanceOf(HrmsApiException.class);

            assertThat(attempts).as("attempts for %s", kind).hasValue(1);
        }
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("a 5xx is retried until attempts run out, then the last failure surfaces")
    void serverErrorIsRetriedToTheLimit() {
        AtomicInteger attempts = new AtomicInteger();
        HrmsRetryExecutor executor = executorWith(policy());

        assertThatThrownBy(() -> executor.execute("GET /payslips", () -> {
            attempts.incrementAndGet();
            throw new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "upstream exploded", 503, null, null, null);
        }))
                .isInstanceOf(HrmsApiException.class)
                .extracting(ex -> ((HrmsApiException) ex).getKind())
                .isEqualTo(HrmsFailureKind.SERVER_ERROR);

        assertThat(attempts).hasValue(4);
        assertThat(slept).hasSize(3);
    }

    @Test
    @DisplayName("a transient failure is absorbed and the call still returns data")
    void succeedsAfterTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        HrmsRetryExecutor executor = executorWith(policy());

        String result = executor.execute("GET /workers", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new HrmsApiException(HrmsFailureKind.TIMEOUT, "read timed out");
            }
            return "page-data";
        });

        assertThat(result).isEqualTo("page-data");
        assertThat(attempts).hasValue(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    @DisplayName("backoff grows exponentially and stops at the ceiling")
    void backoffGrowsThenCaps() {
        AtomicInteger attempts = new AtomicInteger();
        HrmsRetryExecutor executor = executorWith(policy());

        assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
            attempts.incrementAndGet();
            throw new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "still down");
        })).isInstanceOf(HrmsApiException.class);

        // 100ms, 200ms, then capped at the 250ms maximum rather than 400ms.
        assertThat(slept).containsExactly(
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250));
    }

    @Test
    @DisplayName("Retry-After from the server overrides the computed backoff")
    void retryAfterIsHonoured() {
        HrmsRetryExecutor executor = executorWith(policy());

        assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
            throw new HrmsApiException(HrmsFailureKind.RATE_LIMITED, "slow down",
                    429, null, Duration.ofSeconds(2), null);
        })).isInstanceOf(HrmsApiException.class);

        assertThat(slept).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("an absurd Retry-After cannot park the thread indefinitely")
    void retryAfterIsCapped() {
        HrmsProperties.Retry retry = policy();
        retry.setMaxAttempts(2);
        retry.setMaxRetryAfter(Duration.ofSeconds(30));
        HrmsRetryExecutor executor = executorWith(retry);

        assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
            throw new HrmsApiException(HrmsFailureKind.RATE_LIMITED, "come back tomorrow",
                    429, null, Duration.ofHours(6), null);
        })).isInstanceOf(HrmsApiException.class);

        assertThat(slept).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("jitter keeps each delay inside the configured band")
    void jitterStaysWithinBounds() {
        HrmsProperties.Retry retry = policy();
        retry.setJitter(0.5);
        HrmsRetryExecutor executor = executorWith(retry);

        for (int i = 0; i < 200; i++) {
            Duration delay = executor.delayFor(1, null);
            assertThat(delay).isBetween(Duration.ofMillis(50), Duration.ofMillis(150));
        }
    }

    @Test
    @DisplayName("maxAttempts=1 turns retrying off entirely")
    void singleAttemptDisablesRetry() {
        HrmsProperties.Retry retry = policy();
        retry.setMaxAttempts(1);
        AtomicInteger attempts = new AtomicInteger();
        HrmsRetryExecutor executor = executorWith(retry);

        assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
            attempts.incrementAndGet();
            throw new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "down");
        })).isInstanceOf(HrmsApiException.class);

        assertThat(attempts).hasValue(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("an interrupted wait abandons the retries instead of swallowing the interrupt")
    void interruptedWaitStopsRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        HrmsRetryExecutor executor = new HrmsRetryExecutor(policy(), delay -> {
            throw new InterruptedException("shutting down");
        });

        try {
            assertThatThrownBy(() -> executor.execute("GET /workers", () -> {
                attempts.incrementAndGet();
                throw new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "down");
            })).isInstanceOf(HrmsApiException.class);

            assertThat(attempts).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("interrupt flag must be restored for the caller")
                    .isTrue();
        } finally {
            // Clear it so the flag does not leak into the next test on this thread.
            Thread.interrupted();
        }
    }
}
