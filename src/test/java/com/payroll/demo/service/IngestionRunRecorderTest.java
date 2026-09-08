package com.payroll.demo.service;

import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.IngestionRun;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.repository.IngestionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The audit row is the record of what a run did, so what it stores is worth pinning.
 */
class IngestionRunRecorderTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    private IngestionRunRepository runs;
    private IngestionRunRecorder recorder;

    @BeforeEach
    void setUp() {
        runs = mock(IngestionRunRepository.class);
        recorder = new IngestionRunRecorder(runs);
    }

    private static IngestionRun saved(Long id) {
        IngestionRun run = new IngestionRun();
        run.setId(id);
        return run;
    }

    @Test
    @DisplayName("a run opens as RUNNING with its trigger and period recorded")
    void startOpensARunningRow() {
        when(runs.save(any())).thenAnswer(inv -> {
            IngestionRun run = inv.getArgument(0);
            run.setId(42L);
            return run;
        });

        Long id = recorder.start("HRMS_BULK", IngestionTrigger.SCHEDULED, START, END);

        assertThat(id).isEqualTo(42L);
        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(captor.capture());

        IngestionRun run = captor.getValue();
        assertThat(run.getSource()).isEqualTo("HRMS_BULK");
        assertThat(run.getTriggerType()).isEqualTo(IngestionTrigger.SCHEDULED);
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.RUNNING);
        assertThat(run.getPeriodStart()).isEqualTo(START);
        assertThat(run.getPeriodEnd()).isEqualTo(END);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).as("not finished yet").isNull();
    }

    @Test
    @DisplayName("a workers-only run records no period")
    void startWithoutAPeriod() {
        when(runs.save(any())).thenAnswer(inv -> {
            IngestionRun run = inv.getArgument(0);
            run.setId(1L);
            return run;
        });

        recorder.start("HRMS_BULK", IngestionTrigger.MANUAL, null, null);

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(captor.capture());
        assertThat(captor.getValue().getPeriodStart()).isNull();
        assertThat(captor.getValue().getPeriodEnd()).isNull();
    }

    @Test
    @DisplayName("finishing a clean run writes every count and no failure")
    void finishRecordsCounts() {
        when(runs.findById(42L)).thenReturn(Optional.of(saved(42L)));

        RunTally tally = new RunTally();
        tally.countEmployeesFetched(10);
        tally.countPayslipsFetched(4);
        tally.recordWritten(9, 3);
        tally.countHttpAttempt();
        tally.countHttpAttempt();

        recorder.finish(42L, IngestionStatus.SUCCEEDED, null, null, tally);

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(captor.capture());

        IngestionRun run = captor.getValue();
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getEmployeesFetched()).isEqualTo(10);
        assertThat(run.getEmployeesWritten()).isEqualTo(9);
        assertThat(run.getPayslipsFetched()).isEqualTo(4);
        assertThat(run.getPayslipsWritten()).isEqualTo(3);
        assertThat(run.getHttpAttempts()).isEqualTo(2);
        assertThat(run.getFailureKind()).isNull();
        assertThat(run.getFailureDetail()).isNull();
        assertThat(run.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("rejected records are attached to the run and linked back to it")
    void finishAttachesRejectedRecords() {
        when(runs.findById(42L)).thenReturn(Optional.of(saved(42L)));

        RunTally tally = new RunTally();
        tally.reject(IngestionRecordType.WORKER, "W-1", "baseSalary is required");
        tally.reject(IngestionRecordType.PAYSLIP, "PS-1", "lines do not balance");

        recorder.finish(42L, IngestionStatus.PARTIAL, null, null, tally);

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(captor.capture());

        IngestionRun run = captor.getValue();
        assertThat(run.getRecordsRejected()).isEqualTo(2);
        assertThat(run.getErrors()).hasSize(2);
        assertThat(run.getErrors()).allSatisfy(e ->
                assertThat(e.getRun()).as("each error points back at its run").isSameAs(run));
        assertThat(run.getErrors()).extracting(e -> e.getExternalId()).containsExactly("W-1", "PS-1");
    }

    @Test
    @DisplayName("a failed run keeps the kind and the detail")
    void finishRecordsFailure() {
        when(runs.findById(42L)).thenReturn(Optional.of(saved(42L)));

        recorder.finish(42L, IngestionStatus.FAILED, IngestionFailureKind.TIMEOUT,
                "TIMEOUT: GET /workers page 0 timed out", new RunTally());

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(captor.capture());

        assertThat(captor.getValue().getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(captor.getValue().getFailureKind()).isEqualTo(IngestionFailureKind.TIMEOUT);
        assertThat(captor.getValue().getFailureDetail()).contains("timed out");
    }

    @Test
    @DisplayName("closing a run that is gone fails loudly rather than silently doing nothing")
    void finishOnAMissingRunThrows() {
        when(runs.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.finish(99L, IngestionStatus.SUCCEEDED, null, null, new RunTally()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99")
                .hasMessageContaining("vanished");
    }
}
