package com.payroll.demo.hrms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure classification on its own, without a socket.
 *
 * <p>{@code HrmsClientTest} proves the common codes end to end against a real server; this
 * covers the rest of the table and the awkward exception chains, which are impractical to
 * provoke over HTTP.
 */
class HrmsClassificationTest {

    @ParameterizedTest
    @CsvSource({
            "400, BAD_REQUEST",
            "422, BAD_REQUEST",
            "401, UNAUTHORIZED",
            "403, UNAUTHORIZED",
            "404, NOT_FOUND",
            "410, NOT_FOUND",
            "409, CONFLICT",
            "429, RATE_LIMITED",
            "408, TIMEOUT",
            "418, CLIENT_ERROR",
            "451, CLIENT_ERROR",
            "500, SERVER_ERROR",
            "502, SERVER_ERROR",
            "503, SERVER_ERROR",
            "504, SERVER_ERROR",
            "599, SERVER_ERROR",
    })
    void classifiesEveryStatusWeExpect(int status, HrmsFailureKind expected) {
        assertThat(HrmsClient.classifyStatus(status)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 410, 418, 422})
    @DisplayName("no 4xx other than 408 and 429 is ever retried")
    void clientErrorsAreNotRetryable(int status) {
        assertThat(HrmsClient.classifyStatus(status).isRetryable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    @DisplayName("the transient statuses are all retryable")
    void transientStatusesAreRetryable(int status) {
        assertThat(HrmsClient.classifyStatus(status).isRetryable()).isTrue();
    }

    @Test
    @DisplayName("a 3xx reaching the error handler is treated as a server fault, not a client one")
    void unexpectedNonErrorStatusIsServerError() {
        assertThat(HrmsClient.classifyStatus(302)).isEqualTo(HrmsFailureKind.SERVER_ERROR);
    }

    // ------------------------------------------------------------- transport

    @Test
    void readTimeoutIsATimeout() {
        assertThat(HrmsClient.classifyTransport(new HttpTimeoutException("request timed out")))
                .isEqualTo(HrmsFailureKind.TIMEOUT);
    }

    @Test
    @DisplayName("a connect timeout is a timeout too, being a subtype of one")
    void connectTimeoutIsATimeout() {
        assertThat(HrmsClient.classifyTransport(new HttpConnectTimeoutException("connect timed out")))
                .isEqualTo(HrmsFailureKind.TIMEOUT);
    }

    @Test
    void socketTimeoutIsATimeout() {
        assertThat(HrmsClient.classifyTransport(new SocketTimeoutException("read timed out")))
                .isEqualTo(HrmsFailureKind.TIMEOUT);
    }

    @Test
    void refusedConnectionIsTransport() {
        assertThat(HrmsClient.classifyTransport(new ConnectException("Connection refused")))
                .isEqualTo(HrmsFailureKind.TRANSPORT);
    }

    @Test
    void unknownHostIsTransport() {
        assertThat(HrmsClient.classifyTransport(new UnknownHostException("hrms.invalid")))
                .isEqualTo(HrmsFailureKind.TRANSPORT);
    }

    @Test
    @DisplayName("the real cause is found however deeply it is wrapped")
    void walksTheCauseChain() {
        Throwable buried = new IllegalStateException("outer",
                new RuntimeException("middle",
                        new IOException("io", new SocketTimeoutException("read timed out"))));

        assertThat(HrmsClient.classifyTransport(buried)).isEqualTo(HrmsFailureKind.TIMEOUT);
    }

    @Test
    @DisplayName("an unrecognised transport fault is still retryable, since it may be a blip")
    void unrecognisedTransportFaultDefaultsToTransport() {
        assertThat(HrmsClient.classifyTransport(new IOException("connection reset by peer")))
                .isEqualTo(HrmsFailureKind.TRANSPORT);
        assertThat(HrmsFailureKind.TRANSPORT.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("a self-referencing cause does not spin forever")
    void selfReferencingCauseTerminates() {
        // initCause cannot point at itself, so build the loop through a second exception.
        Throwable first = new RuntimeException("first");
        Throwable second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(HrmsClient.classifyTransport(first)).isEqualTo(HrmsFailureKind.TRANSPORT);
    }

    @Test
    @DisplayName("an absurdly deep chain is abandoned rather than walked forever")
    void veryDeepChainTerminates() {
        Throwable deep = new SocketTimeoutException("the real cause, buried too deep to find");
        for (int i = 0; i < 50; i++) {
            deep = new RuntimeException("layer " + i, deep);
        }
        // Past the depth cap the timeout is no longer visible; still classified, still retryable.
        assertThat(HrmsClient.classifyTransport(deep)).isEqualTo(HrmsFailureKind.TRANSPORT);
    }

    @Test
    void nullIsClassifiedRatherThanThrowing() {
        assertThat(HrmsClient.classifyTransport(null)).isEqualTo(HrmsFailureKind.TRANSPORT);
    }

    // ------------------------------------------------------------ exception

    @Test
    @DisplayName("describe() carries what an operator needs and nothing else")
    void describeSummarisesTheFailure() {
        HrmsApiException ex = new HrmsApiException(HrmsFailureKind.BAD_REQUEST,
                "GET /workers failed with HTTP 400", 400, "{\"error\":\"bad filter\"}",
                Duration.ofSeconds(5), null);

        assertThat(ex.describe())
                .contains("BAD_REQUEST")
                .contains("HTTP 400")
                .contains("bad filter");
        assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("describe() copes with a transport failure that has no status or body")
    void describeWithoutStatusOrBody() {
        HrmsApiException ex = new HrmsApiException(HrmsFailureKind.TRANSPORT, "could not connect");

        assertThat(ex.describe()).isEqualTo("TRANSPORT: could not connect");
        assertThat(ex.getStatusCode()).isNull();
        assertThat(ex.getResponseBody()).isNull();
        assertThat(ex.getRetryAfter()).isNull();
    }

    @Test
    void describeIgnoresABlankBody() {
        HrmsApiException ex = new HrmsApiException(HrmsFailureKind.SERVER_ERROR,
                "HTTP 500", 500, "   ", null, null);

        assertThat(ex.describe()).doesNotContain("body=");
    }

    @Test
    @DisplayName("a huge body is truncated and whitespace collapsed before it is stored anywhere")
    void bodyIsTruncatedAndCollapsed() {
        HrmsApiException ex = new HrmsApiException(HrmsFailureKind.BAD_REQUEST,
                "HTTP 400", 400, "  a\n\n   b  " + "x".repeat(5000), null, null);

        assertThat(ex.getResponseBody()).startsWith("a b");
        assertThat(ex.getResponseBody()).endsWith("[truncated]");
        assertThat(ex.getResponseBody().length())
                .isLessThanOrEqualTo(HrmsApiException.MAX_BODY_SNIPPET + 20);
    }

    @Test
    void keepsTheCauseWhenGivenOne() {
        IOException cause = new IOException("socket closed");
        assertThat(new HrmsApiException(HrmsFailureKind.TRANSPORT, "failed", cause)).hasCause(cause);
    }
}
