package com.payroll.demo.hrms;

import com.payroll.demo.config.HrmsProperties;
import com.payroll.demo.dto.HrmsDtos.HrmsPage;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Thin, retry-free reader over the HRMS REST API.
 *
 * <p>Its whole job is to turn every way this call can go wrong into one
 * {@link HrmsApiException} carrying a {@link HrmsFailureKind}. Retrying belongs to
 * {@link HrmsRetryExecutor}; deciding what a failure <em>means</em> belongs here, and
 * keeping the two apart is what makes both testable.
 */
@Component
public class HrmsClient {

    private static final Logger log = LoggerFactory.getLogger(HrmsClient.class);

    /** Bound on how much of an error body we pull off the socket before classifying. */
    private static final int ERROR_BODY_READ_LIMIT = 8 * 1024;

    /** Guard against a pathological (cyclic or absurdly deep) exception chain. */
    private static final int MAX_CAUSE_DEPTH = 12;

    private final RestClient restClient;
    private final HrmsProperties properties;

    public HrmsClient(RestClient hrmsRestClient, HrmsProperties properties) {
        this.restClient = hrmsRestClient;
        this.properties = properties;
    }

    /**
     * Fetches one page of workers.
     *
     * @param updatedSince optional incremental-sync watermark; {@code null} pulls everything
     */
    public HrmsPage<HrmsWorker> fetchWorkers(int page, int size, LocalDate updatedSince) {
        return get(
                uri -> {
                    uri.path("/workers").queryParam("page", page).queryParam("size", size);
                    if (updatedSince != null) {
                        uri.queryParam("updatedSince", updatedSince);
                    }
                },
                new ParameterizedTypeReference<HrmsPage<HrmsWorker>>() {
                },
                "GET /workers page " + page);
    }

    /** Fetches one page of payslips for a pay period. */
    public HrmsPage<HrmsPayslip> fetchPayslips(LocalDate periodStart, LocalDate periodEnd, int page, int size) {
        return get(
                uri -> {
                    uri.path("/payslips").queryParam("page", page).queryParam("size", size);
                    if (periodStart != null) {
                        uri.queryParam("periodStart", periodStart);
                    }
                    if (periodEnd != null) {
                        uri.queryParam("periodEnd", periodEnd);
                    }
                },
                new ParameterizedTypeReference<HrmsPage<HrmsPayslip>>() {
                },
                "GET /payslips page " + page);
    }

    private <T> T get(UriCustomizer uriCustomizer, ParameterizedTypeReference<T> type, String description) {
        try {
            T body = restClient.get()
                    .uri(builder -> {
                        uriCustomizer.apply(builder);
                        return builder.build();
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw toApiException(response, description);
                    })
                    .body(type);

            if (body == null) {
                throw new HrmsApiException(HrmsFailureKind.MALFORMED_RESPONSE,
                        description + " returned an empty body");
            }
            return body;

        } catch (HrmsApiException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            // No HTTP response was ever produced: timeout, refused connection, DNS, reset.
            HrmsFailureKind kind = classifyTransport(ex);
            // The JDK reports a timeout as "Request cancelled", which tells an operator
            // nothing. Name the limits that were actually in force instead.
            String detail = kind == HrmsFailureKind.TIMEOUT
                    ? "timed out (connect-timeout=" + properties.getConnectTimeout()
                    + ", read-timeout=" + properties.getReadTimeout() + ")"
                    : "could not reach the HRMS: " + rootMessage(ex);
            throw new HrmsApiException(kind, description + " " + detail, ex);
        } catch (UnknownContentTypeException ex) {
            throw new HrmsApiException(HrmsFailureKind.MALFORMED_RESPONSE,
                    description + " returned unexpected content type " + ex.getContentType(),
                    ex.getStatusCode().value(),
                    new String(ex.getResponseBody(), StandardCharsets.UTF_8), null, ex);
        } catch (RestClientException ex) {
            // A 2xx we could not deserialise is a contract break, so not worth retrying.
            throw new HrmsApiException(HrmsFailureKind.MALFORMED_RESPONSE,
                    description + " returned a body that could not be parsed: " + rootMessage(ex), ex);
        }
    }

    /** Maps an HTTP error response onto a classified exception, body included for diagnosis. */
    private HrmsApiException toApiException(ClientHttpResponse response, String description) {
        int status = readStatus(response);
        String body = readBodySafely(response);
        Duration retryAfter = readRetryAfter(response);
        HrmsFailureKind kind = classifyStatus(status);

        if (kind == HrmsFailureKind.UNAUTHORIZED) {
            // The token travels in a default header; keep it out of every log line.
            log.error("HRMS rejected our credentials on {} (HTTP {}). Check payroll.hrms.api-token.",
                    description, status);
        }
        return new HrmsApiException(kind,
                description + " failed with HTTP " + status,
                status, body, retryAfter, null);
    }

    /** Visible for testing. */
    static HrmsFailureKind classifyStatus(int status) {
        if (status >= 500) {
            return HrmsFailureKind.SERVER_ERROR;
        }
        return switch (status) {
            case 400, 422 -> HrmsFailureKind.BAD_REQUEST;
            case 401, 403 -> HrmsFailureKind.UNAUTHORIZED;
            case 404, 410 -> HrmsFailureKind.NOT_FOUND;
            case 409 -> HrmsFailureKind.CONFLICT;
            case 429 -> HrmsFailureKind.RATE_LIMITED;
            // 408 is the server saying it gave up waiting: same shape as our own timeout.
            case 408 -> HrmsFailureKind.TIMEOUT;
            default -> status >= 400 ? HrmsFailureKind.CLIENT_ERROR : HrmsFailureKind.SERVER_ERROR;
        };
    }

    /**
     * Separates running out of time from other transport faults.
     *
     * <p>Both are retryable, but a timeout usually means the timeout or the page size needs
     * tuning, while a refused connection means the HRMS is down or the URL is wrong.
     * Visible for testing.
     */
    static HrmsFailureKind classifyTransport(Throwable ex) {
        Throwable cursor = ex;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            // HttpConnectTimeoutException extends HttpTimeoutException, so both land here.
            if (cursor instanceof HttpTimeoutException || cursor instanceof SocketTimeoutException) {
                return HrmsFailureKind.TIMEOUT;
            }
            if (cursor instanceof ConnectException || cursor instanceof UnknownHostException) {
                return HrmsFailureKind.TRANSPORT;
            }
            Throwable next = cursor.getCause();
            if (next == cursor) {
                break;
            }
            cursor = next;
        }
        return HrmsFailureKind.TRANSPORT;
    }

    private static int readStatus(ClientHttpResponse response) {
        try {
            return response.getStatusCode().value();
        } catch (IOException e) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }

    /** Reads a bounded slice of the error body; a body-read failure must not mask the real error. */
    private static String readBodySafely(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            byte[] bytes = in.readNBytes(ERROR_BODY_READ_LIMIT);
            return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Parses {@code Retry-After} in either delta-seconds or HTTP-date form. */
    private static Duration readRetryAfter(ClientHttpResponse response) {
        String header;
        try {
            header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        } catch (RuntimeException e) {
            return null;
        }
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // Fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration until = Duration.between(ZonedDateTime.now(when.getZone()), when);
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException e) {
            log.debug("Unparseable Retry-After header from HRMS: {}", value);
            return null;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        for (int depth = 0;
             cursor.getCause() != null && cursor.getCause() != cursor && depth < MAX_CAUSE_DEPTH;
             depth++) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }

    /** Small alias so both fetch methods can share {@link #get}. */
    @FunctionalInterface
    private interface UriCustomizer {
        void apply(UriBuilder builder);
    }
}
