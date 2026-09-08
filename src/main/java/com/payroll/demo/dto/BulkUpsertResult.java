package com.payroll.demo.dto;


import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.IngestionRunError;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.service.RunTally;

import java.util.List;

/**
 * Outcome of a bulk upsert, including whether the batch was committed or rolled back.
 */
public record BulkUpsertResult(
        Long runId,
        IngestionStatus status,
        boolean committed,
        int employeesFetched,
        int payslipsFetched,
        int recordsRejected,
        int httpAttempts,
        BulkWriteCounts written,
        IngestionFailureKind failureKind,
        String failureDetail,
        List<RejectedRecord> rejectedRecords) {


    public record RejectedRecord(IngestionRecordType recordType, String externalId, String reason) {
    }

    public static BulkUpsertResult applied(Long runId, IngestionStatus status, BulkWriteCounts counts, RunTally tally) {
        return new BulkUpsertResult(runId, status, true,
                tally.getEmployeesFetched(), tally.getPayslipsFetched(),
                tally.getRecordsRejected(), tally.getHttpAttempts(),
                counts,
                null, null, rejections(tally));
    }

    /** Strict mode stopped before the write; nothing reached the database. */
    public static BulkUpsertResult rejected(Long runId, RunTally tally) {
        return new BulkUpsertResult(runId, IngestionStatus.FAILED, false,
                tally.getEmployeesFetched(), tally.getPayslipsFetched(),
                tally.getRecordsRejected(), tally.getHttpAttempts(),
                null, IngestionFailureKind.INTERNAL_ERROR,
                "strict mode: " + tally.getRecordsRejected()
                        + " record(s) failed validation; nothing was written",
                rejections(tally));
    }

    public static BulkUpsertResult failed(Long runId, IngestionFailureKind kind, String detail, RunTally tally) {
        return new BulkUpsertResult(runId, IngestionStatus.FAILED, false,
                tally.getEmployeesFetched(), tally.getPayslipsFetched(),
                tally.getRecordsRejected(), tally.getHttpAttempts(),
                null, kind, detail, rejections(tally));
    }

    private static List<RejectedRecord> rejections(RunTally tally) {
        return tally.getErrors().stream()
                .map(BulkUpsertResult::toRejection)
                .toList();
    }

    private static RejectedRecord toRejection(IngestionRunError error) {
        return new RejectedRecord(error.getRecordType(), error.getExternalId(), error.getReason());
    }
}
