package com.payroll.demo.service;

import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionRun;
import com.payroll.demo.domain.IngestionRunError;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.repository.IngestionRunRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IngestionRunRecorder.class);

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

        Long id = runs.save(run).getId();
        log.info("Opened ingestion run {} (source={}, trigger={}, period {}..{})",
                id, source, trigger, periodStart, periodEnd);
        return id;
    }

    /** Closes the run, writing final counts and any rejected records. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId,
                       IngestionStatus status,
                       IngestionFailureKind failureKind,
                       String failureDetail,
                       RunTally tally) {
        IngestionRun run = runs.findById(runId).orElseThrow(() -> {
            log.error("Ingestion run {} vanished before it could be closed; its outcome is lost", runId);
            return new IllegalStateException("ingestion run " + runId + " vanished before it could be closed");
        });

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

        if (status == IngestionStatus.FAILED) {
            log.error("Closed ingestion run {} as {} ({}): {}",
                    runId, status, failureKind, failureDetail);
        } else {
            log.info("Closed ingestion run {} as {} ({} rejected record(s) stored)",
                    runId, status, tally.getErrors().size());
        }
    }
}
