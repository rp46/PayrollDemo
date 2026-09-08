package com.payroll.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One HRMS record this run refused to write, with the reason it was refused.
 */
@Entity
@Table(name = "ingestion_run_errors")
@Getter
@Setter
@NoArgsConstructor
public class IngestionRunError {

    /** Matches the reason column width; longer reasons are truncated before persisting. */
    public static final int MAX_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private IngestionRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 20)
    private IngestionRecordType recordType;

    /** The HRMS identifier of the offending record, when it had one. */
    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(nullable = false, length = MAX_REASON_LENGTH)
    private String reason;

    public static IngestionRunError of(IngestionRecordType type, String externalId, String reason) {
        IngestionRunError error = new IngestionRunError();
        error.setRecordType(type);
        error.setExternalId(trim(externalId, 100));
        error.setReason(trim(reason == null ? "unspecified" : reason, MAX_REASON_LENGTH));
        return error;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
