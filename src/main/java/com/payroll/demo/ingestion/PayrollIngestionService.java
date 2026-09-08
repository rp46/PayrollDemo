package com.payroll.demo.ingestion;

import com.payroll.demo.hrms.HrmsApiException;
import com.payroll.demo.hrms.HrmsClient;
import com.payroll.demo.hrms.HrmsDtos.HrmsPage;
import com.payroll.demo.hrms.HrmsDtos.HrmsPayslip;
import com.payroll.demo.hrms.HrmsDtos.HrmsWorker;
import com.payroll.demo.hrms.HrmsProperties;
import com.payroll.demo.hrms.HrmsRetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pulls payroll data from the HRMS and writes it into the {@code payroll} database.
 *
 * <p>The method is deliberately not transactional. Each record is committed by
 * {@link PayrollUpsertService} in its own transaction, so a run that dies on page 40 keeps
 * the 39 pages it already stored, and re-running it is safe because every write is an
 * upsert on a natural key.
 *
 * <p>Failure handling is split three ways:
 * <ul>
 *   <li><b>Retryable transport and 5xx faults</b> are retried with backoff by
 *       {@link HrmsRetryExecutor}, and only abort the run once attempts are exhausted.</li>
 *   <li><b>Non-retryable API failures</b> (400, 401, 404, unparseable body) abort the run
 *       immediately — nothing about waiting would change the answer.</li>
 *   <li><b>Bad individual records</b> are rejected, recorded against the run, and skipped;
 *       the run continues and finishes {@link IngestionStatus#PARTIAL}.</li>
 * </ul>
 */
@Service
public class PayrollIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PayrollIngestionService.class);

    private static final String SOURCE = "HRMS";

    private final HrmsClient client;
    private final HrmsRetryExecutor retry;
    private final HrmsProperties properties;
    private final PayrollUpsertService upserts;
    private final IngestionRunRecorder recorder;

    /**
     * Serialises runs within this instance so a scheduled sync and a manual trigger cannot
     * race to create the same department or pay period.
     *
     * <p>Single-instance only. Running more than one replica needs a shared lock — a
     * Postgres advisory lock held for the run is the natural fit.
     */
    private final ReentrantLock runLock = new ReentrantLock();

    public PayrollIngestionService(HrmsClient client,
                                   HrmsRetryExecutor retry,
                                   HrmsProperties properties,
                                   PayrollUpsertService upserts,
                                   IngestionRunRecorder recorder) {
        this.client = client;
        this.retry = retry;
        this.properties = properties;
        this.upserts = upserts;
        this.recorder = recorder;
    }

    /**
     * Runs a full sync: workers first, then payslips for the given period.
     *
     * <p>Workers lead because a payslip is meaningless without the employee it belongs to.
     *
     * @param periodStart start of the pay period to pull payslips for; {@code null} skips payslips
     * @param periodEnd   end of that period
     * @return the id of the {@code ingestion_runs} row describing what happened
     */
    public Long ingest(IngestionTrigger trigger, LocalDate periodStart, LocalDate periodEnd, LocalDate updatedSince) {
        if (!runLock.tryLock()) {
            throw new IngestionInProgressException();
        }
        try {
            return runIngestion(trigger, periodStart, periodEnd, updatedSince);
        } finally {
            runLock.unlock();
        }
    }

    private Long runIngestion(IngestionTrigger trigger,
                              LocalDate periodStart,
                              LocalDate periodEnd,
                              LocalDate updatedSince) {
        Long runId = recorder.start(SOURCE, trigger, periodStart, periodEnd);
        RunTally tally = new RunTally();

        IngestionStatus status;
        IngestionFailureKind failureKind = null;
        String failureDetail = null;

        try {
            log.info("Ingestion run {} started ({} trigger, period {}..{})",
                    runId, trigger, periodStart, periodEnd);

            ingestWorkers(tally, updatedSince);
            if (periodStart != null && periodEnd != null) {
                ingestPayslips(tally, periodStart, periodEnd);
            } else {
                log.info("Ingestion run {}: no pay period requested, skipping payslips", runId);
            }

            status = tally.hasRejections() ? IngestionStatus.PARTIAL : IngestionStatus.SUCCEEDED;

        } catch (HrmsApiException ex) {
            // The upstream call could not be completed. Everything written so far stays.
            status = IngestionStatus.FAILED;
            failureKind = IngestionFailureKind.from(ex.getKind());
            failureDetail = ex.describe();
            log.error("Ingestion run {} aborted: {}", runId, failureDetail);

        } catch (DataAccessException ex) {
            status = IngestionStatus.FAILED;
            failureKind = IngestionFailureKind.INTERNAL_ERROR;
            failureDetail = "database failure: " + ex.getMostSpecificCause().getMessage();
            log.error("Ingestion run {} aborted on a database failure", runId, ex);

        } catch (RuntimeException ex) {
            status = IngestionStatus.FAILED;
            failureKind = IngestionFailureKind.INTERNAL_ERROR;
            failureDetail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Ingestion run {} aborted unexpectedly", runId, ex);
        }

        recorder.finish(runId, status, failureKind, failureDetail, tally);
        log.info("Ingestion run {} finished {}: employees {}/{} written, payslips {}/{} written, "
                        + "{} rejected, {} HTTP attempt(s)",
                runId, status,
                tally.getEmployeesWritten(), tally.getEmployeesFetched(),
                tally.getPayslipsWritten(), tally.getPayslipsFetched(),
                tally.getRecordsRejected(), tally.getHttpAttempts());
        return runId;
    }

    private void ingestWorkers(RunTally tally, LocalDate updatedSince) {
        int size = properties.getPageSize();

        for (int page = 0; page < properties.getMaxPages(); page++) {
            final int currentPage = page;
            HrmsPage<HrmsWorker> result = retry.execute("GET /workers page " + currentPage, () -> {
                tally.countHttpAttempt();
                return client.fetchWorkers(currentPage, size, updatedSince);
            });

            List<HrmsWorker> workers = result.safeItems();
            tally.countEmployeesFetched(workers.size());

            for (HrmsWorker worker : workers) {
                storeWorker(worker, tally);
            }
            if (!result.moreAvailable(size)) {
                return;
            }
        }
        log.warn("Stopped paging /workers at the {}-page cap; the feed may be larger than expected "
                + "or hasMore may be wrong upstream", properties.getMaxPages());
    }

    private void ingestPayslips(RunTally tally, LocalDate periodStart, LocalDate periodEnd) {
        int size = properties.getPageSize();

        for (int page = 0; page < properties.getMaxPages(); page++) {
            final int currentPage = page;
            HrmsPage<HrmsPayslip> result = retry.execute("GET /payslips page " + currentPage, () -> {
                tally.countHttpAttempt();
                return client.fetchPayslips(periodStart, periodEnd, currentPage, size);
            });

            List<HrmsPayslip> slips = result.safeItems();
            tally.countPayslipsFetched(slips.size());

            for (HrmsPayslip slip : slips) {
                storePayslip(slip, tally);
            }
            if (!result.moreAvailable(size)) {
                return;
            }
        }
        log.warn("Stopped paging /payslips at the {}-page cap", properties.getMaxPages());
    }

    private void storeWorker(HrmsWorker worker, RunTally tally) {
        try {
            upserts.upsertWorker(worker);
            tally.countEmployeeWritten();
        } catch (IngestionRecordException ex) {
            tally.reject(IngestionRecordType.WORKER, ex.getExternalId(), ex.getMessage());
            log.warn("Rejected worker {}: {}", ex.getExternalId(), ex.getMessage());
        } catch (DataAccessException ex) {
            // A constraint we did not pre-check, most often a duplicate email across two
            // worker ids. Only this record rolled back, so keep going.
            String id = worker == null ? null : worker.workerId();
            String reason = "database rejected the record: " + ex.getMostSpecificCause().getMessage();
            tally.reject(IngestionRecordType.WORKER, id, reason);
            log.warn("Database rejected worker {}: {}", id, reason);
        }
    }

    private void storePayslip(HrmsPayslip slip, RunTally tally) {
        try {
            upserts.upsertPayslip(slip);
            tally.countPayslipWritten();
        } catch (IngestionRecordException ex) {
            tally.reject(IngestionRecordType.PAYSLIP, ex.getExternalId(), ex.getMessage());
            log.warn("Rejected payslip {}: {}", ex.getExternalId(), ex.getMessage());
        } catch (DataAccessException ex) {
            String id = slip == null ? null : slip.payslipId();
            String reason = "database rejected the record: " + ex.getMostSpecificCause().getMessage();
            tally.reject(IngestionRecordType.PAYSLIP, id, reason);
            log.warn("Database rejected payslip {}: {}", id, reason);
        }
    }
}
