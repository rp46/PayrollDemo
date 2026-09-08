package com.payroll.demo.ingestion;

import com.payroll.demo.ingestion.IngestionDtos.IngestionRequest;
import com.payroll.demo.ingestion.IngestionDtos.IngestionRunView;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Trigger a sync and inspect what previous ones did.
 *
 * <p>A failed run still returns 200 with the run record: the HTTP call to <em>this</em>
 * service succeeded, and the upstream failure is reported in the body, where the status
 * and {@code failureKind} say exactly what went wrong.
 */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private static final int MAX_RUNS_PAGE = 100;

    private final PayrollIngestionService ingestion;
    private final IngestionRunRepository runs;

    public IngestionController(PayrollIngestionService ingestion, IngestionRunRepository runs) {
        this.ingestion = ingestion;
        this.runs = runs;
    }

    /** Runs a sync synchronously and returns the resulting run record. */
    @PostMapping("/runs")
    public ResponseEntity<IngestionRunView> trigger(@RequestBody(required = false) IngestionRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        IngestionRequest body = request == null ? new IngestionRequest(null, null, null) : request;

        Long runId = ingestion.ingest(
                IngestionTrigger.MANUAL, body.periodStart(), body.periodEnd(), body.updatedSince());

        IngestionRunView view = findRun(runId);
        URI location = uriBuilder.path("/api/ingestion/runs/{id}").buildAndExpand(runId).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(view);
    }

    @GetMapping("/runs")
    public List<IngestionRunView> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        int capped = Math.clamp(limit, 1, MAX_RUNS_PAGE);
        return runs.findAllByOrderByStartedAtDesc(PageRequest.of(0, capped)).stream()
                .map(run -> IngestionDtos.toView(run, false))
                .toList();
    }

    @GetMapping("/runs/{id}")
    public IngestionRunView run(@PathVariable Long id) {
        return findRun(id);
    }

    // The repository applies an entity graph, so the error list is already loaded by the
    // time the returned run leaves its transaction.
    private IngestionRunView findRun(Long id) {
        return runs.findWithErrorsById(id)
                .map(run -> IngestionDtos.toView(run, true))
                .orElseThrow(() -> new IngestionRunNotFoundException(id));
    }
}
