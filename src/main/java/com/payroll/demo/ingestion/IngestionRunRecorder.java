package com.payroll.demo.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Persists the audit trail for an ingestion run.
 *
 * <p>Both methods use {@code REQUIRES_NEW} so the audit row survives independently of the
 * data transactions around it. The whole value of the run record is that it is still there
 * after the run fails.
 */
@Service
public class IngestionRunRecorder {

    private final IngestionRunRepository runs;

    public IngestionRunRecorder(IngestionRunRepository runs) {
        this.runs = runs;
    }

    /** Opens a {@link IngestionStatus#RUNNING} row and returns its id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String source, IngestionTrigger trigger, LocalDate periodStart, LocalDate periodEnd) {
        IngestionRun run = new IngestionRun();
        run.setSource(source);
        run.setTriggerType(trigger);
        run.setStatus(IngestionStatus.RUNNING);
        run.setPeriodStart(periodStart);
        run.setPeriodEnd(periodEnd);
        run.setStartedAt(OffsetDateTime.now());
        return runs.save(run).getId();
    }

    /** Closes the run, writing final counts and any rejected records. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId,
                       IngestionStatus status,
                       IngestionFailureKind failureKind,
                       String failureDetail,
                       RunTally tally) {
        IngestionRun run = runs.findById(runId).orElseThrow(
                () -> new IllegalStateException("ingestion run " + runId + " vanished before it could be closed"));

        run.setStatus(status);
        run.setFinishedAt(OffsetDateTime.now());
        run.setFailureKind(failureKind);
        run.setFailureDetail(failureDetail);
        run.setEmployeesFetched(tally.getEmployeesFetched());
        run.setEmployeesWritten(tally.getEmployeesWritten());
        run.setPayslipsFetched(tally.getPayslipsFetched());
        run.setPayslipsWritten(tally.getPayslipsWritten());
        run.setRecordsRejected(tally.getRecordsRejected());
        run.setHttpAttempts(tally.getHttpAttempts());

        for (IngestionRunError error : tally.getErrors()) {
            run.addError(error);
        }
        runs.save(run);
    }
}
