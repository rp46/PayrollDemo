package com.payroll.demo.ingestion;

import com.payroll.demo.hrms.HrmsApiException;
import com.payroll.demo.hrms.HrmsClient;
import com.payroll.demo.hrms.HrmsDtos.HrmsPage;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslip;
import com.payroll.demo.hrms.HrmsDtos.HrmsWorker;
import com.payroll.demo.hrms.HrmsFailureKind;
import com.payroll.demo.hrms.HrmsProperties;
import com.payroll.demo.hrms.HrmsRetryExecutor;
import com.payroll.demo.ingestion.PayrollUpsertService.UpsertOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the run as a whole reacts to each class of failure: which ones abort it, which ones
 * are absorbed, and what the audit row ends up saying.
 */
class PayrollIngestionServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 31);
    private static final long RUN_ID = 42L;

    private HrmsClient client;
    private PayrollUpsertService upserts;
    private IngestionRunRecorder recorder;
    private HrmsProperties properties;
    private PayrollIngestionService service;

    @BeforeEach
    void setUp() {
        client = mock(HrmsClient.class);
        upserts = mock(PayrollUpsertService.class);
        recorder = mock(IngestionRunRecorder.class);

        properties = new HrmsProperties();
        properties.setPageSize(2);
        properties.setMaxPages(10);
        properties.getRetry().setMaxAttempts(3);

        when(recorder.start(any(), any(), any(), any())).thenReturn(RUN_ID);

        // Real retry logic, but no real waiting.
        HrmsRetryExecutor retry = new HrmsRetryExecutor(properties.getRetry(), delay -> {
        });
        service = new PayrollIngestionService(client, retry, properties, upserts, recorder);
    }

    private static HrmsWorker worker(String id) {
        return new HrmsWorker(id, "Priya", "Raman", id + "@example.com",
                LocalDate.of(2023, 4, 1), null, "ACTIVE", "ENG", "Engineering", new BigDecimal("100"));
    }

    private static HrmsPayslip payslip(String id, String workerId) {
        return new HrmsPayslip(id, workerId, PERIOD_START, PERIOD_END, LocalDate.of(2026, 9, 1),
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("90"), "PAID", List.of());
    }

    private static <T> HrmsPage<T> page(List<T> items, boolean hasMore) {
        return new HrmsPage<>(items, 0, items.size(), (long) items.size(), hasMore);
    }

    private static <T> HrmsPage<T> emptyPage() {
        return new HrmsPage<>(List.of(), 0, 0, 0L, false);
    }

    /** Captures what the run was ultimately recorded as. */
    private record Recorded(IngestionStatus status, IngestionFailureKind kind, String detail, RunTally tally) {
    }

    private Recorded captureFinish() {
        ArgumentCaptor<IngestionStatus> status = ArgumentCaptor.forClass(IngestionStatus.class);
        ArgumentCaptor<IngestionFailureKind> kind = ArgumentCaptor.forClass(IngestionFailureKind.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RunTally> tally = ArgumentCaptor.forClass(RunTally.class);

        verify(recorder).finish(eq(RUN_ID), status.capture(), kind.capture(), detail.capture(), tally.capture());
        return new Recorded(status.getValue(), kind.getValue(), detail.getValue(), tally.getValue());
    }

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("a clean sync writes everything and records SUCCEEDED")
    void cleanRunSucceeds() throws Exception {
        when(client.fetchWorkers(eq(0), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-2")), false));
        when(client.fetchPayslips(eq(PERIOD_START), eq(PERIOD_END), eq(0), anyInt()))
                .thenReturn(page(List.of(payslip("PS-1", "E-1")), false));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);
        when(upserts.upsertPayslip(any())).thenReturn(UpsertOutcome.CREATED);

        Long runId = service.ingest(IngestionTrigger.MANUAL, PERIOD_START, PERIOD_END, null);

        assertThat(runId).isEqualTo(RUN_ID);
        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(recorded.kind()).isNull();
        assertThat(recorded.tally().getEmployeesFetched()).isEqualTo(2);
        assertThat(recorded.tally().getEmployeesWritten()).isEqualTo(2);
        assertThat(recorded.tally().getPayslipsWritten()).isEqualTo(1);
        assertThat(recorded.tally().getHttpAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("paging continues while the feed says there is more")
    void followsPagination() {
        when(client.fetchWorkers(eq(0), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-2")), true));
        when(client.fetchWorkers(eq(1), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-3")), false));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        verify(client).fetchWorkers(eq(0), anyInt(), isNull());
        verify(client).fetchWorkers(eq(1), anyInt(), isNull());
        assertThat(captureFinish().tally().getEmployeesWritten()).isEqualTo(3);
    }

    @Test
    @DisplayName("payslips are skipped when no pay period was asked for")
    void skipsPayslipsWithoutAPeriod() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenReturn(emptyPage());

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        verify(client, never()).fetchPayslips(any(), any(), anyInt(), anyInt());
        assertThat(captureFinish().status()).isEqualTo(IngestionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a broken hasMore upstream cannot spin forever")
    void stopsAtThePageCap() {
        properties.setMaxPages(3);
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1")), true));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        verify(client, times(3)).fetchWorkers(anyInt(), anyInt(), isNull());
        assertThat(captureFinish().status()).isEqualTo(IngestionStatus.SUCCEEDED);
    }

    // ---------------------------------------------------- upstream API failures

    @Test
    @DisplayName("a 400 aborts the run on the first attempt, with the reason recorded")
    void badRequestAbortsWithoutRetrying() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenThrow(
                new HrmsApiException(HrmsFailureKind.BAD_REQUEST, "GET /workers failed with HTTP 400",
                        400, "{\"error\":\"unsupported page size\"}", null, null));

        service.ingest(IngestionTrigger.MANUAL, PERIOD_START, PERIOD_END, null);

        verify(client, times(1)).fetchWorkers(anyInt(), anyInt(), isNull());
        verify(client, never()).fetchPayslips(any(), any(), anyInt(), anyInt());

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(recorded.kind()).isEqualTo(IngestionFailureKind.BAD_REQUEST);
        assertThat(recorded.detail()).contains("unsupported page size");
        assertThat(recorded.tally().getHttpAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 401 aborts the run and is recorded as an auth problem, not a generic failure")
    void unauthorizedAbortsRun() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenThrow(
                new HrmsApiException(HrmsFailureKind.UNAUTHORIZED, "token expired", 401, null, null, null));

        service.ingest(IngestionTrigger.MANUAL, PERIOD_START, PERIOD_END, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(recorded.kind()).isEqualTo(IngestionFailureKind.UNAUTHORIZED);
        verify(client, times(1)).fetchWorkers(anyInt(), anyInt(), isNull());
    }

    @Test
    @DisplayName("a persistent 5xx is retried to the limit, then fails the run")
    void serverErrorIsRetriedThenFailsTheRun() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenThrow(
                new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "HTTP 503", 503, null, null, null));

        service.ingest(IngestionTrigger.MANUAL, PERIOD_START, PERIOD_END, null);

        verify(client, times(3)).fetchWorkers(anyInt(), anyInt(), isNull());

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(recorded.kind()).isEqualTo(IngestionFailureKind.SERVER_ERROR);
        assertThat(recorded.tally().getHttpAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a timeout that clears on retry does not cost the run")
    void transientTimeoutRecovers() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenThrow(new HrmsApiException(HrmsFailureKind.TIMEOUT, "read timed out"))
                .thenReturn(page(List.of(worker("E-1")), false));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(recorded.tally().getEmployeesWritten()).isEqualTo(1);
        assertThat(recorded.tally().getHttpAttempts())
                .as("the failed attempt is still counted")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("workers already stored survive a failure during the payslip phase")
    void partialProgressIsKept() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1")), false));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);
        when(client.fetchPayslips(any(), any(), anyInt(), anyInt())).thenThrow(
                new HrmsApiException(HrmsFailureKind.SERVER_ERROR, "HTTP 500", 500, null, null, null));

        service.ingest(IngestionTrigger.MANUAL, PERIOD_START, PERIOD_END, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(recorded.tally().getEmployeesWritten())
                .as("the worker phase committed before the payslip phase failed")
                .isEqualTo(1);
    }

    // ------------------------------------------------------- record-level faults

    @Test
    @DisplayName("one bad record is rejected and the rest of the page still lands")
    void badRecordDoesNotStopTheRun() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-BAD"), worker("E-3")), false));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);
        doThrow(new IngestionRecordException(IngestionRecordType.WORKER, "E-BAD", "baseSalary is required"))
                .when(upserts).upsertWorker(worker("E-BAD"));

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(recorded.tally().getEmployeesFetched()).isEqualTo(3);
        assertThat(recorded.tally().getEmployeesWritten()).isEqualTo(2);
        assertThat(recorded.tally().getRecordsRejected()).isEqualTo(1);
        assertThat(recorded.tally().getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getRecordType()).isEqualTo(IngestionRecordType.WORKER);
            assertThat(error.getExternalId()).isEqualTo("E-BAD");
            assertThat(error.getReason()).isEqualTo("baseSalary is required");
        });
    }

    @Test
    @DisplayName("a constraint violation is a record-level rejection, not a dead run")
    void constraintViolationRejectsOnlyThatRecord() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull()))
                .thenReturn(page(List.of(worker("E-1"), worker("E-2")), false));
        when(upserts.upsertWorker(any())).thenReturn(UpsertOutcome.CREATED);
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint employees_email_key"))
                .when(upserts).upsertWorker(worker("E-2"));

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(recorded.tally().getEmployeesWritten()).isEqualTo(1);
        assertThat(recorded.tally().getRecordsRejected()).isEqualTo(1);
        assertThat(recorded.tally().getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getReason()).contains("employees_email_key"));
    }

    @Test
    @DisplayName("a payslip for an unknown worker is rejected without killing the batch")
    void rejectedPayslipIsRecorded() {
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenReturn(emptyPage());
        when(client.fetchPayslips(any(), any(), anyInt(), anyInt()))
                .thenReturn(page(List.of(payslip("PS-1", "E-GHOST")), false));
        doThrow(new IngestionRecordException(IngestionRecordType.PAYSLIP, "PS-1", "no employee with code E-GHOST"))
                .when(upserts).upsertPayslip(any());

        service.ingest(IngestionTrigger.MANUAL, PERIOD_START, PERIOD_END, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(recorded.tally().getPayslipsWritten()).isZero();
        assertThat(recorded.tally().getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getRecordType()).isEqualTo(IngestionRecordType.PAYSLIP));
    }

    @Test
    @DisplayName("the stored error list is capped even when every record is bad")
    void errorListIsCapped() {
        List<HrmsWorker> manyBadWorkers = new java.util.ArrayList<>();
        for (int i = 0; i < RunTally.MAX_RECORDED_ERRORS + 50; i++) {
            manyBadWorkers.add(worker("E-" + i));
        }
        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenReturn(page(manyBadWorkers, false));
        doThrow(new IngestionRecordException(IngestionRecordType.WORKER, "E-x", "bad"))
                .when(upserts).upsertWorker(any());

        service.ingest(IngestionTrigger.MANUAL, null, null, null);

        Recorded recorded = captureFinish();
        assertThat(recorded.tally().getRecordsRejected()).isEqualTo(RunTally.MAX_RECORDED_ERRORS + 50);
        assertThat(recorded.tally().getErrors())
                .as("every rejection is counted, but only the first few hundred are stored")
                .hasSize(RunTally.MAX_RECORDED_ERRORS);
    }

    // --------------------------------------------------------------- concurrency

    @Test
    @DisplayName("a second run is refused while one is in flight")
    void concurrentRunsAreRefused() throws Exception {
        CountDownLatch runStarted = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);

        when(client.fetchWorkers(anyInt(), anyInt(), isNull())).thenAnswer(invocation -> {
            runStarted.countDown();
            releaseRun.await(5, TimeUnit.SECONDS);
            return emptyPage();
        });

        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = new Thread(() -> service.ingest(IngestionTrigger.SCHEDULED, null, null, null));
        first.start();

        try {
            assertThat(runStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.ingest(IngestionTrigger.MANUAL, null, null, null))
                    .isInstanceOf(IngestionInProgressException.class);
        } finally {
            releaseRun.countDown();
            first.join(5000);
        }
        assertThat(secondFailure.get()).isNull();
    }
}
