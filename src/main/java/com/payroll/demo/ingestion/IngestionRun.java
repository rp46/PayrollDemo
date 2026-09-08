package com.payroll.demo.ingestion;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Audit row for one attempt to pull payroll data from the HRMS.
 *
 * <p>This is the operator-facing answer to "did last night's sync work, and if not, why
 * not" — without it, a 400 from the HRMS is just a line in a log file nobody reads.
 */
@Entity
@Table(name = "ingestion_runs")
@Getter
@Setter
@NoArgsConstructor
public class IngestionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private IngestionTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IngestionStatus status = IngestionStatus.RUNNING;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "employees_fetched", nullable = false)
    private int employeesFetched;

    @Column(name = "employees_written", nullable = false)
    private int employeesWritten;

    @Column(name = "payslips_fetched", nullable = false)
    private int payslipsFetched;

    @Column(name = "payslips_written", nullable = false)
    private int payslipsWritten;

    @Column(name = "records_rejected", nullable = false)
    private int recordsRejected;

    /** Total HTTP attempts, retries included; a spike here means the HRMS is struggling. */
    @Column(name = "http_attempts", nullable = false)
    private int httpAttempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind", length = 40)
    private IngestionFailureKind failureKind;

    @Column(name = "failure_detail", columnDefinition = "text")
    private String failureDetail;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IngestionRunError> errors = new ArrayList<>();

    public void addError(IngestionRunError error) {
        errors.add(error);
        error.setRun(this);
    }
}
