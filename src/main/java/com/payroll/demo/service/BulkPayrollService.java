package com.payroll.demo.service;

import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.dto.BulkUpsertResult;
import com.payroll.demo.dto.BulkWriteCounts;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslipLine;
import com.payroll.demo.dto.HrmsDtos.HrmsWorker;
import com.payroll.demo.dto.PayrollRows.DepartmentRow;
import com.payroll.demo.dto.PayrollRows.EmployeeRow;
import com.payroll.demo.dto.PayrollRows.PayPeriodRow;
import com.payroll.demo.dto.PayrollRows.PayrollBatch;
import com.payroll.demo.dto.PayrollRows.PayslipKey;
import com.payroll.demo.dto.PayrollRows.PayslipLineRow;
import com.payroll.demo.dto.PayrollRows.PayslipRow;
import com.payroll.demo.dto.PayrollRows.PeriodKey;
import com.payroll.demo.exception.BulkUpsertInProgressException;
import com.payroll.demo.exception.IngestionRecordException;
import com.payroll.demo.repository.BulkPayrollRepository;
import com.payroll.demo.util.FailureDetails;
import com.payroll.demo.util.FailureDetails.Failure;
import com.payroll.demo.util.MoneyUtils;
import com.payroll.demo.util.SingleFlightGuard;
import com.payroll.demo.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Atomic ingestion: transforms the whole HRMS feed, then writes it in one transaction.
 *
 * <p>Three phases, deliberately separated:
 * <ol>
 *   <li><b>Fetch</b> — every page pulled over HTTP by {@link HrmsPageReader}, with retries.
 *       No transaction is open, because holding a database connection across network I/O
 *       ties up the pool for as long as the slowest upstream response, and a retry storm
 *       would exhaust it.</li>
 *   <li><b>Transform</b> — validate with {@link HrmsRecordMapper} and map onto
 *       {@code PayrollRows}. Pure, no I/O. Records that cannot be stored are rejected here
 *       with a reason.</li>
 *   <li><b>Write</b> — one call to {@link BulkPayrollRepository#applyBatch}, which is the
 *       transaction. Everything commits or everything rolls back.</li>
 * </ol>
 *
 * <p>The consequence worth stating plainly: nothing is written until the entire feed has
 * been fetched and validated. That is the trade for atomicity — a run either lands whole
 * or leaves the database exactly as it was.
 */
@Service
public class BulkPayrollService {

    private static final Logger log = LoggerFactory.getLogger(BulkPayrollService.class);

    private static final String SOURCE = "HRMS_BULK";

    private final HrmsPageReader pages;
    private final HrmsRecordMapper mapper;
    private final BulkPayrollRepository repository;
    private final IngestionRunRecorder recorder;

    private final SingleFlightGuard guard = new SingleFlightGuard();

    public BulkPayrollService(HrmsPageReader pages,
                              HrmsRecordMapper mapper,
                              BulkPayrollRepository repository,
                              IngestionRunRecorder recorder) {
        this.pages = pages;
        this.mapper = mapper;
        this.repository = repository;
        this.recorder = recorder;
    }

    /**
     * Runs the bulk upsert.
     *
     * @param trigger what asked for this run, recorded on the audit row
     * @param strict  when true, any rejected record aborts the run before anything is
     *                written — use it when a partial import is worse than no import
     * @return what happened, including every rejection and its reason
     */
    public BulkUpsertResult upsertAll(IngestionTrigger trigger,
                                      LocalDate periodStart,
                                      LocalDate periodEnd,
                                      LocalDate updatedSince,
                                      boolean strict) {
        return guard.run(BulkUpsertInProgressException::new,
                () -> run(trigger, periodStart, periodEnd, updatedSince, strict));
    }

    private BulkUpsertResult run(IngestionTrigger trigger, LocalDate periodStart, LocalDate periodEnd,
                                 LocalDate updatedSince, boolean strict) {
        Long runId = recorder.start(SOURCE, trigger, periodStart, periodEnd);
        RunTally tally = new RunTally();
        log.info("Bulk upsert run {} started ({} trigger, period {}..{}, strict={})",
                runId, trigger, periodStart, periodEnd, strict);

        try {
            // --- 1. Fetch (no transaction held) -----------------------------
            List<HrmsWorker> workers = pages.collectWorkers(updatedSince, tally::countHttpAttempt);
            tally.countEmployeesFetched(workers.size());

            List<HrmsPayslip> payslips = List.of();
            if (periodStart != null && periodEnd != null) {
                payslips = pages.collectPayslips(periodStart, periodEnd, tally::countHttpAttempt);
                tally.countPayslipsFetched(payslips.size());
            }

            // --- 2. Transform (pure) ----------------------------------------
            PayrollBatch batch = transform(workers, payslips, tally);

            if (strict && tally.hasRejections()) {
                log.warn("Bulk upsert run {} aborted before writing: strict mode and {} rejected record(s)",
                        runId, tally.getRecordsRejected());
                recorder.finish(runId, IngestionStatus.FAILED, IngestionFailureKind.INTERNAL_ERROR,
                        "strict mode: " + tally.getRecordsRejected() + " record(s) failed validation; nothing written",
                        tally);
                return BulkUpsertResult.rejected(runId, tally);
            }

            // --- 3. Write (one transaction: commit or rollback) -------------
            BulkWriteCounts counts = repository.applyBatch(batch);
            tally.recordWritten(counts.employees(), counts.payslips());

            IngestionStatus status = tally.hasRejections() ? IngestionStatus.PARTIAL : IngestionStatus.SUCCEEDED;
            recorder.finish(runId, status, null, null, tally);
            return BulkUpsertResult.applied(runId, status, counts, tally);

        } catch (RuntimeException ex) {
            // A fetch failure means nothing was ever written; a database failure means the
            // batch was rolled back. Either way the audit row survives, because the
            // recorder writes in its own REQUIRES_NEW transaction.
            Failure failure = FailureDetails.classify(ex, "batch rolled back");
            log.error("Bulk upsert run {} did not complete: {}", runId, failure.detail(), ex);
            recorder.finish(runId, IngestionStatus.FAILED, failure.kind(), failure.detail(), tally);
            return BulkUpsertResult.failed(runId, failure.kind(), failure.detail(), tally);
        }
    }

    // -------------------------------------------------------------- transform

    /**
     * Maps validated HRMS records onto payroll rows.
     *
     * <p>Visible for testing: this is the tier's real work, and it needs no database.
     */
    PayrollBatch transform(List<HrmsWorker> workers, List<HrmsPayslip> payslips, RunTally tally) {
        // Departments are deduplicated by code; the feed repeats them on every worker.
        Map<String, DepartmentRow> departments = new LinkedHashMap<>();
        Map<String, EmployeeRow> employees = new LinkedHashMap<>();

        for (HrmsWorker worker : workers) {
            try {
                mapper.validateWorker(worker);
                String code = TextUtils.strip(worker.workerId());

                String departmentCode = TextUtils.blankToNull(worker.departmentCode());
                if (departmentCode != null) {
                    departments.putIfAbsent(departmentCode,
                            new DepartmentRow(departmentCode, mapper.departmentNameFor(worker)));
                }
                // Last one wins if the feed repeats a worker id within the same batch;
                // an ON CONFLICT batch cannot update the same key twice in one statement.
                employees.put(code, new EmployeeRow(
                        code,
                        TextUtils.strip(worker.firstName()),
                        TextUtils.strip(worker.lastName()),
                        TextUtils.strip(worker.email()),
                        worker.hireDate(),
                        worker.terminationDate(),
                        mapper.toEmployeeStatus(code, worker.employmentStatus()),
                        departmentCode,
                        MoneyUtils.normalize(worker.baseSalary())));

            } catch (IngestionRecordException ex) {
                tally.reject(IngestionRecordType.WORKER, ex.getExternalId(), ex.getMessage());
                log.warn("Rejected worker {}: {}", ex.getExternalId(), ex.getMessage());
            }
        }

        PayslipTransform slips = transformPayslips(payslips, employees.keySet(), tally);

        return new PayrollBatch(
                List.copyOf(departments.values()),
                List.copyOf(employees.values()),
                slips.periods(),
                slips.payslips());
    }

    private PayslipTransform transformPayslips(List<HrmsPayslip> payslips,
                                               Set<String> employeeCodesInBatch,
                                               RunTally tally) {
        if (payslips.isEmpty()) {
            return new PayslipTransform(List.of(), List.of());
        }

        // A payDate is only mandatory when the period does not exist yet, so find out which
        // ones do. This read is outside the write transaction; the pay period upsert is
        // ON CONFLICT DO NOTHING, so a period appearing in between is harmless.
        Set<PeriodKey> candidates = new HashSet<>();
        Set<String> referencedWorkers = new HashSet<>();
        for (HrmsPayslip slip : payslips) {
            if (slip == null) {
                continue;
            }
            if (slip.periodStart() != null && slip.periodEnd() != null) {
                candidates.add(new PeriodKey(slip.periodStart(), slip.periodEnd()));
            }
            if (slip.workerId() != null) {
                referencedWorkers.add(TextUtils.strip(slip.workerId()));
            }
        }
        Set<PeriodKey> existingPeriods = repository.findExistingPeriods(candidates);

        // A payslip whose worker is neither in this batch nor already stored would blow up
        // the whole transaction on a not-null violation. Reject it as a record instead.
        Set<String> knownEmployees = new HashSet<>(employeeCodesInBatch);
        knownEmployees.addAll(repository.findExistingEmployeeCodes(referencedWorkers));

        Map<PeriodKey, PayPeriodRow> periods = new LinkedHashMap<>();
        Map<PayslipKey, PayslipRow> rows = new LinkedHashMap<>();

        for (HrmsPayslip slip : payslips) {
            try {
                PeriodKey periodKey = (slip == null || slip.periodStart() == null || slip.periodEnd() == null)
                        ? null
                        : new PeriodKey(slip.periodStart(), slip.periodEnd());
                boolean mustCreatePeriod = periodKey == null || !existingPeriods.contains(periodKey);

                mapper.validatePayslip(slip, mustCreatePeriod);

                String workerId = TextUtils.strip(slip.workerId());
                if (!knownEmployees.contains(workerId)) {
                    throw new IngestionRecordException(IngestionRecordType.PAYSLIP, slip.payslipId(),
                            "no employee with code " + workerId + " in this batch or in the database");
                }

                if (mustCreatePeriod) {
                    periods.putIfAbsent(periodKey,
                            new PayPeriodRow(slip.periodStart(), slip.periodEnd(), slip.payDate()));
                }

                List<PayslipLineRow> lines = new ArrayList<>();
                for (HrmsPayslipLine line : slip.safeLines()) {
                    lines.add(new PayslipLineRow(
                            TextUtils.strip(line.componentCode()),
                            mapper.toComponentType(slip.payslipId(), line.componentType(), line.componentCode()),
                            TextUtils.strip(line.description()),
                            MoneyUtils.normalize(line.amount())));
                }

                PayslipKey key = new PayslipKey(workerId, slip.periodStart(), slip.periodEnd());
                rows.put(key, new PayslipRow(
                        key,
                        MoneyUtils.normalize(slip.grossPay()),
                        MoneyUtils.normalize(slip.totalDeductions()),
                        MoneyUtils.normalize(slip.netPay()),
                        mapper.toPayslipStatus(slip.payslipId(), slip.status()),
                        List.copyOf(lines)));

            } catch (IngestionRecordException ex) {
                tally.reject(IngestionRecordType.PAYSLIP, ex.getExternalId(), ex.getMessage());
                log.warn("Rejected payslip {}: {}", ex.getExternalId(), ex.getMessage());
            }
        }
        return new PayslipTransform(List.copyOf(periods.values()), List.copyOf(rows.values()));
    }

    private record PayslipTransform(List<PayPeriodRow> periods, List<PayslipRow> payslips) {
    }
}
