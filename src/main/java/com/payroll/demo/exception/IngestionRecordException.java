package com.payroll.demo.exception;

import com.payroll.demo.domain.IngestionRecordType;

import java.io.Serial;

/**
 * One upstream record could not be stored.
 *
 * <p>Record-level, not run-level: the record is logged against the run and ingestion moves
 * on. A single malformed payslip must not cost us the other nine hundred in the same feed.
 */
public class IngestionRecordException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final IngestionRecordType recordType;
    private final String externalId;

    public IngestionRecordException(IngestionRecordType recordType, String externalId, String reason) {
        super(reason);
        this.recordType = recordType;
        this.externalId = externalId;
    }

    public IngestionRecordType getRecordType() {
        return recordType;
    }

    public String getExternalId() {
        return externalId;
    }
}
