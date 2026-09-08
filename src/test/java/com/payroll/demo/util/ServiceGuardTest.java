package com.payroll.demo.util;

import com.payroll.demo.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service tier contract: whatever goes wrong, what comes out is a
 * {@link ApiException} the web layer can map to a status.
 */
class ServiceGuardTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceGuardTest.class);

    @Test
    void returnsTheResultOfTheWork() {
        assertThat(ServiceGuard.call(log, "do a thing", () -> "done")).isEqualTo("done");
    }

    @Test
    @DisplayName("an exception the application raised deliberately passes through unchanged")
    void passesDomainExceptionsThrough() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "find employee", () -> {
            throw ApiException.notFound("No employee with code " + "E-NOPE");
        }))
                .isInstanceOf(ApiException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::getStatus)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a database failure becomes 503, because the request was not the problem")
    void databaseFailureBecomesServiceUnavailable() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "list employees", () -> {
            throw new DataIntegrityViolationException("connection reset");
        }))
                .isInstanceOf(ApiException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::getStatus)
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a null pointer never reaches the controller as a 5xx")
    void nullPointerBecomesBadRequest() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "build pay details", () -> {
            String nothing = null;
            return nothing.length();
        }))
                .isInstanceOf(ApiException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::getStatus)
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the message it hands back does not leak internals")
    void nullPointerMessageIsSafe() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "build pay details", () -> {
            String nothing = null;
            return nothing.length();
        }))
                .hasMessageContaining("a required value was missing")
                // The JVM message names the local variable and the call site.
                .hasMessageNotContaining("nothing")
                .hasMessageNotContaining("String.length");
    }

    @Test
    @DisplayName("an IllegalArgumentException keeps its message, which is written to be read")
    void illegalArgumentKeepsItsMessage() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "parse something", () -> {
            throw new IllegalArgumentException("status must be one of DRAFT, APPROVED, PAID");
        }))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("status must be one of DRAFT, APPROVED, PAID");
    }

    @Test
    @DisplayName("the rest of the bad-argument family is handled the same way")
    void otherBadArgumentFailuresBecomeBadRequest() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "op", () -> {
            throw new IllegalStateException("bad state");
        })).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> ServiceGuard.call(log, "op", () -> {
            throw new ArithmeticException("rounding necessary");
        }))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not be represented exactly");

        assertThatThrownBy(() -> ServiceGuard.call(log, "op", () -> {
            throw new IndexOutOfBoundsException(9);
        })).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the original failure is kept as the cause so nothing is lost")
    void originalCauseIsPreserved() {
        NullPointerException original = new NullPointerException("boom");

        assertThatThrownBy(() -> ServiceGuard.call(log, "op", () -> {
            throw original;
        })).hasCause(original);
    }

    @Test
    @DisplayName("something genuinely unforeseen is not disguised as a client error")
    void trulyUnexpectedFailuresPropagate() {
        assertThatThrownBy(() -> ServiceGuard.call(log, "op", () -> {
            throw new UnsupportedOperationException("not implemented");
        })).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void voidFormRunsTheWork() {
        StringBuilder ran = new StringBuilder();
        ServiceGuard.run(log, "do a void thing", () -> ran.append("ran"));
        assertThat(ran).hasToString("ran");
    }
}
