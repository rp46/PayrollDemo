package com.payroll.demo.ingestion;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {

    List<IngestionRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "errors")
    Optional<IngestionRun> findWithErrorsById(Long id);
}
