package com.payroll.demo.hrms;

import com.payroll.demo.hrms.HrmsDtos.HrmsPage;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslip;
import com.payroll.demo.hrms.HrmsDtos.HrmsWorker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the client against a real socket, because the failures that matter here —
 * a read timeout, a refused connection, a truncated body — do not reproduce against a
 * mocked HTTP stack.
 */
class HrmsClientTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private volatile Responder responder;
    private final List<String> receivedQueries = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.createContext("/hrms/v1", exchange -> {
            receivedQueries.add(exchange.getRequestURI().toString());
            try {
                responder.respond(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hrms/v1";
    }

    private HrmsProperties properties() {
        HrmsProperties properties = new HrmsProperties();
        properties.setBaseUrl(baseUrl());
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private HrmsClient clientFor(HrmsProperties properties) {
        return new HrmsClient(new HrmsClientConfig().hrmsRestClient(properties), properties);
    }

    private HrmsClient client() {
        return clientFor(properties());
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json", body);
    }

    // ---------------------------------------------------------------- happy path

    @Test
    @DisplayName("a normal page of workers is parsed, paging flags and all")
    void parsesWorkerPage() {
        responder = exchange -> sendJson(exchange, 200, """
                {
                  "items": [
                    {
                      "workerId": "E-2001",
                      "firstName": "Priya",
                      "lastName": "Raman",
                      "email": "priya.raman@example.com",
                      "hireDate": "2023-04-01",
                      "employmentStatus": "ACTIVE",
                      "departmentCode": "ENG",
                      "departmentName": "Engineering",
                      "baseSalary": 1200000.00,
                      "unexpectedNewFieldFromUpstream": "ignored"
                    }
                  ],
                  "page": 0,
                  "size": 100,
                  "totalItems": 1,
                  "hasMore": false
                }
                """);

        HrmsPage<HrmsWorker> page = client().fetchWorkers(0, 100, null);

        assertThat(page.safeItems()).hasSize(1);
        HrmsWorker worker = page.safeItems().getFirst();
        assertThat(worker.workerId()).isEqualTo("E-2001");
        assertThat(worker.hireDate()).isEqualTo(LocalDate.of(2023, 4, 1));
        assertThat(worker.baseSalary()).isEqualByComparingTo(new BigDecimal("1200000.00"));
        assertThat(page.moreAvailable(100)).isFalse();
    }

    @Test
    @DisplayName("payslip filters are sent as query parameters")
    void sendsPayslipQueryParameters() {
        responder = exchange -> sendJson(exchange, 200, "{\"items\": [], \"hasMore\": false}");

        HrmsPage<HrmsPayslip> page =
                client().fetchPayslips(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 2, 50);

        assertThat(page.safeItems()).isEmpty();
        assertThat(receivedQueries).hasSize(1);
        assertThat(receivedQueries.getFirst())
                .contains("page=2")
                .contains("size=50")
                .contains("periodStart=2026-08-01")
                .contains("periodEnd=2026-08-31");
    }

    @Test
    @DisplayName("the bearer token is attached when one is configured")
    void sendsBearerToken() {
        List<String> auth = new ArrayList<>();
        responder = exchange -> {
            auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            sendJson(exchange, 200, "{\"items\": [], \"hasMore\": false}");
        };

        HrmsProperties properties = properties();
        properties.setApiToken("secret-token");
        clientFor(properties).fetchWorkers(0, 10, null);

        assertThat(auth).containsExactly("Bearer secret-token");
    }

    @Test
    @DisplayName("an absent hasMore falls back to a full page meaning there is probably more")
    void inferesPagingWhenFlagAbsent() {
        responder = exchange -> sendJson(exchange, 200,
                "{\"items\": [{\"workerId\": \"E-1\"}, {\"workerId\": \"E-2\"}]}");

        HrmsPage<HrmsWorker> page = client().fetchWorkers(0, 2, null);

        assertThat(page.moreAvailable(2)).isTrue();
        assertThat(page.moreAvailable(10)).isFalse();
    }

    // ------------------------------------------------------------ 4xx: permanent

    @Test
    @DisplayName("400 is classified as permanent and keeps the explanation from the body")
    void badRequestIsPermanentAndKeepsTheBody() {
        responder = exchange -> sendJson(exchange, 400,
                "{\"error\":\"periodStart must be before periodEnd\",\"code\":\"INVALID_RANGE\"}");

        assertThatThrownBy(() -> client().fetchPayslips(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), 0, 100))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.BAD_REQUEST);
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getStatusCode()).isEqualTo(400);
                    // Without the body a 400 is undiagnosable, so it must survive.
                    assertThat(ex.getResponseBody()).contains("periodStart must be before periodEnd");
                    assertThat(ex.describe()).contains("BAD_REQUEST").contains("HTTP 400");
                });
    }

    @Test
    @DisplayName("422 is treated the same way as 400")
    void unprocessableEntityIsPermanent() {
        responder = exchange -> sendJson(exchange, 422, "{\"error\":\"unknown field\"}");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.BAD_REQUEST));
    }

    @Test
    @DisplayName("401 is permanent and never echoes the token")
    void unauthorizedIsPermanent() {
        responder = exchange -> sendJson(exchange, 401, "{\"error\":\"token expired\"}");

        HrmsProperties properties = properties();
        properties.setApiToken("super-secret");

        assertThatThrownBy(() -> clientFor(properties).fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.UNAUTHORIZED);
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.describe()).doesNotContain("super-secret");
                });
    }

    @Test
    @DisplayName("404 is permanent")
    void notFoundIsPermanent() {
        responder = exchange -> sendJson(exchange, 404, "{\"error\":\"no such tenant\"}");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.NOT_FOUND);
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    // ------------------------------------------------------------ 5xx: retryable

    @Test
    @DisplayName("500 is retryable")
    void serverErrorIsRetryable() {
        responder = exchange -> sendJson(exchange, 500, "{\"error\":\"internal\"}");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.SERVER_ERROR);
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.getStatusCode()).isEqualTo(500);
                });
    }

    @Test
    @DisplayName("502, 503 and 504 are all retryable server errors")
    void allFiveHundredsAreRetryable() {
        for (int status : new int[]{502, 503, 504}) {
            responder = exchange -> send(exchange, status, "application/json", "{}");
            assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                    .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                        assertThat(ex.getKind()).as("status %s", status).isEqualTo(HrmsFailureKind.SERVER_ERROR);
                        assertThat(ex.isRetryable()).isTrue();
                    });
        }
    }

    @Test
    @DisplayName("503 with Retry-After surfaces the delay the server asked for")
    void serviceUnavailableCarriesRetryAfter() {
        responder = exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "7");
            sendJson(exchange, 503, "{\"error\":\"maintenance\"}");
        };

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.SERVER_ERROR);
                    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    @DisplayName("429 is rate limiting, not a client mistake")
    void tooManyRequestsIsRateLimited() {
        responder = exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            sendJson(exchange, 429, "{\"error\":\"quota exceeded\"}");
        };

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.RATE_LIMITED);
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    @DisplayName("an unparseable Retry-After is ignored rather than fatal")
    void malformedRetryAfterIsIgnored() {
        responder = exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "soon-ish");
            sendJson(exchange, 503, "{}");
        };

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.SERVER_ERROR);
                    assertThat(ex.getRetryAfter()).isNull();
                });
    }

    // --------------------------------------------------------------- timeouts

    @Test
    @DisplayName("a server that never answers trips the read timeout instead of hanging")
    void readTimeoutIsClassifiedAsTimeout() {
        responder = exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sendJson(exchange, 200, "{\"items\": []}");
        };

        HrmsProperties properties = properties();
        properties.setReadTimeout(Duration.ofMillis(400));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> clientFor(properties).fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.TIMEOUT);
                    assertThat(ex.isRetryable()).isTrue();
                    // The message must name the limit that fired, not just say "cancelled".
                    assertThat(ex).hasMessageContaining("read-timeout=PT0.4S");
                });

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(elapsedMs)
                .as("must give up near the configured timeout, not wait for the server")
                .isLessThan(2500);
    }

    @Test
    @DisplayName("408 from the server is a timeout too")
    void requestTimeoutStatusIsATimeout() {
        responder = exchange -> send(exchange, 408, "application/json", "{}");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.TIMEOUT);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("a refused connection is transport, not timeout, and is retryable")
    void connectionRefusedIsTransport() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        HrmsProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + deadPort + "/hrms/v1");

        assertThatThrownBy(() -> clientFor(properties).fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.TRANSPORT);
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.getStatusCode()).isNull();
                });
    }

    // ------------------------------------------------------- malformed responses

    @Test
    @DisplayName("a 200 carrying nonsense is a contract break, so it is not retried")
    void unparseableBodyIsPermanent() {
        responder = exchange -> sendJson(exchange, 200, "this is definitely not json");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.MALFORMED_RESPONSE);
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("an HTML error page instead of JSON is reported as malformed, not parsed")
    void htmlInsteadOfJsonIsMalformed() {
        responder = exchange -> send(exchange, 200, "text/html", "<html><body>Gateway</body></html>");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(HrmsFailureKind.MALFORMED_RESPONSE));
    }

    // ------------------------------------------------------------- classification

    @Test
    @DisplayName("status classification covers the ranges, not just the listed codes")
    void statusClassificationIsExhaustive() {
        assertThat(HrmsClient.classifyStatus(400)).isEqualTo(HrmsFailureKind.BAD_REQUEST);
        assertThat(HrmsClient.classifyStatus(401)).isEqualTo(HrmsFailureKind.UNAUTHORIZED);
        assertThat(HrmsClient.classifyStatus(403)).isEqualTo(HrmsFailureKind.UNAUTHORIZED);
        assertThat(HrmsClient.classifyStatus(409)).isEqualTo(HrmsFailureKind.CONFLICT);
        assertThat(HrmsClient.classifyStatus(418)).isEqualTo(HrmsFailureKind.CLIENT_ERROR);
        assertThat(HrmsClient.classifyStatus(429)).isEqualTo(HrmsFailureKind.RATE_LIMITED);
        assertThat(HrmsClient.classifyStatus(500)).isEqualTo(HrmsFailureKind.SERVER_ERROR);
        assertThat(HrmsClient.classifyStatus(599)).isEqualTo(HrmsFailureKind.SERVER_ERROR);
    }

    @Test
    @DisplayName("a very large error body is truncated before it reaches a log or a column")
    void oversizedErrorBodyIsTruncated() {
        String huge = "x".repeat(50_000);
        responder = exchange -> sendJson(exchange, 400, "{\"error\":\"" + huge + "\"}");

        assertThatThrownBy(() -> client().fetchWorkers(0, 100, null))
                .isInstanceOfSatisfying(HrmsApiException.class, ex -> {
                    assertThat(ex.getResponseBody()).hasSizeLessThan(HrmsApiException.MAX_BODY_SNIPPET + 40);
                    assertThat(ex.getResponseBody()).endsWith("[truncated]");
                });
    }
}
