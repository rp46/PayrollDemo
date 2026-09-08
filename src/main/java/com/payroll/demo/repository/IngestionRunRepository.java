package com.payroll.demo.repository;

import com.payroll.demo.domain.IngestionRun;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persists the ingestion audit trail.
 *
 * <p>Write-only in practice: {@code save} and {@code findById} from {@link JpaRepository}
 * are all {@link com.payroll.demo.service.IngestionRunRecorder} needs. The rows are read
 * back with SQL, not through this application.
 */
public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {
}
