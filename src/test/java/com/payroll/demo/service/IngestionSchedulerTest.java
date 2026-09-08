package com.payroll.demo.service;

import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.dto.BulkUpsertResult;
import com.payroll.demo.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduled sync. A fixed clock makes the period it picks assertable.
 */
class IngestionSchedulerTest {

    private static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static BulkUpsertResult result(IngestionStatus status, boolean committed) {
        return new BulkUpsertResult(11L, status, committed, 3, 2, 0, 3,
                null, null, null, List.of());
    }

    @Test
    @DisplayName("it syncs the whole of the current calendar month, as a SCHEDULED run")
    void syncsTheCurrentMonth() {
        BulkPayrollService ingestion = mock(BulkPayrollService.class);
        when(ingestion.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(result(IngestionStatus.SUCCEEDED, true));

        new IngestionScheduler(ingestion, clockAt("2026-08-17T02:15:00Z")).syncCurrentPeriod();

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        verify(ingestion).upsertAll(eq(IngestionTrigger.SCHEDULED),
                start.capture(), end.capture(), eq(null), eq(false));

        assertThat(start.getValue()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(end.getValue()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("month length is taken from the calendar, including a leap February")
    void handlesShortAndLeapMonths() {
        BulkPayrollService ingestion = mock(BulkPayrollService.class);
        when(ingestion.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(result(IngestionStatus.SUCCEEDED, true));

        new IngestionScheduler(ingestion, clockAt("2028-02-10T02:15:00Z")).syncCurrentPeriod();

        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        verify(ingestion).upsertAll(any(), any(), end.capture(), any(), anyBoolean());
        assertThat(end.getValue()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("scheduled runs are not strict, so one bad record does not discard the night")
    void scheduledRunsAreNotStrict() {
        BulkPayrollService ingestion = mock(BulkPayrollService.class);
        when(ingestion.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(result(IngestionStatus.PARTIAL, true));

        new IngestionScheduler(ingestion, clockAt("2026-08-17T02:15:00Z")).syncCurrentPeriod();

        verify(ingestion).upsertAll(any(), any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("a run already in progress is skipped quietly, not rethrown into the scheduler")
    void concurrentRunIsSkipped() {
        BulkPayrollService ingestion = mock(BulkPayrollService.class);
        when(ingestion.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(ApiException.conflict("already running"));

        assertThatCode(() -> new IngestionScheduler(ingestion, clockAt("2026-08-17T02:15:00Z"))
                .syncCurrentPeriod()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unexpected failure is logged, not thrown, so the next tick still fires")
    void unexpectedFailureDoesNotEscape() {
        BulkPayrollService ingestion = mock(BulkPayrollService.class);
        when(ingestion.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("something broke"));

        assertThatCode(() -> new IngestionScheduler(ingestion, clockAt("2026-08-17T02:15:00Z"))
                .syncCurrentPeriod()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a run that rolled back is reported, not treated as success")
    void reportsARolledBackRun() {
        BulkPayrollService ingestion = mock(BulkPayrollService.class);
        when(ingestion.upsertAll(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(result(IngestionStatus.FAILED, false));

        assertThatCode(() -> new IngestionScheduler(ingestion, clockAt("2026-08-17T02:15:00Z"))
                .syncCurrentPeriod()).doesNotThrowAnyException();

        verify(ingestion).upsertAll(any(), any(), any(), any(), anyBoolean());
    }
}
