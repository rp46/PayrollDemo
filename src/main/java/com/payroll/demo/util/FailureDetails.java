package com.payroll.demo.util;

import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.hrms.HrmsApiException;
import org.springframework.dao.DataAccessException;

/**
 * Turns whatever aborted a run into the kind and detail stored on the audit row.
 *
 * <p>Both ingestion paths ended a run the same way, with the same three-branch catch. That
 * classification is a single decision and now lives in one place.
 */
public final class FailureDetails {

    private FailureDetails() {
    }

    /** A classified failure, ready for {@code ingestion_runs.failure_kind} and {@code failure_detail}. */
    public record Failure(IngestionFailureKind kind, String detail) {
    }

    /**
     * @param databaseContext short phrase describing what the database was doing, e.g.
     *                        {@code "batch rolled back"} for the atomic path
     */
    public static Failure classify(Throwable error, String databaseContext) {
        return switch (error) {
            case HrmsApiException hrms ->
                    new Failure(IngestionFailureKind.from(hrms.getKind()), hrms.describe());
            case DataAccessException dae ->
                    new Failure(IngestionFailureKind.INTERNAL_ERROR,
                            databaseContext + ": " + dae.getMostSpecificCause().getMessage());
            default ->
                    new Failure(IngestionFailureKind.INTERNAL_ERROR,
                            error.getClass().getSimpleName() + ": " + error.getMessage());
        };
    }
}
