package com.payroll.demo.ingestion;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response shapes for the ingestion endpoints. Entities are not serialised directly,
 * since open-in-view is off and the error collection is lazy.
 */
public final class IngestionDtos {

    private IngestionDtos() {
    }

    /** Request body for triggering a sync. All fields optional. */
    public record IngestionRequest(LocalDate periodStart, LocalDate periodEnd, LocalDate updatedSince) {
    }

    public record IngestionRunView(
            Long id,
            String source,
            IngestionTrigger triggerType,
            IngestionStatus status,
            LocalDate periodStart,
            LocalDate periodEnd,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            int employeesFetched,
            int employeesWritten,
            int payslipsFetched,
            int payslipsWritten,
            int recordsRejected,
            int httpAttempts,
            IngestionFailureKind failureKind,
            String failureDetail,
            List<RejectedRecordView> rejectedRecords) {
    }

    public record RejectedRecordView(IngestionRecordType recordType, String externalId, String reason) {
    }

    static IngestionRunView toView(IngestionRun run, boolean includeErrors) {
        List<RejectedRecordView> rejected = includeErrors
                ? run.getErrors().stream()
                .map(e -> new RejectedRecordView(e.getRecordType(), e.getExternalId(), e.getReason()))
                .toList()
                : null;

        return new IngestionRunView(
                run.getId(),
                run.getSource(),
                run.getTriggerType(),
                run.getStatus(),
                run.getPeriodStart(),
                run.getPeriodEnd(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getEmployeesFetched(),
                run.getEmployeesWritten(),
                run.getPayslipsFetched(),
                run.getPayslipsWritten(),
                run.getRecordsRejected(),
                run.getHttpAttempts(),
                run.getFailureKind(),
                run.getFailureDetail(),
                rejected);
    }
}
