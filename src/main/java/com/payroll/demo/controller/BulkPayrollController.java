package com.payroll.demo.controller;

import com.payroll.demo.domain.IngestionTrigger;
import com.payroll.demo.dto.BulkUpsertResult;
import com.payroll.demo.service.BulkPayrollService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Triggers the bulk payroll upsert. That is its only job.
 *
 * <p>No fetching, no transformation, no transaction, no database. It reads the request,
 * calls the service, and hands back what the service reports. Keeping it this thin is what
 * lets the service be tested without a servlet and the repository without HTTP.
 */
@RestController
@RequestMapping("/api/payroll")
public class BulkPayrollController {

    private final BulkPayrollService service;

    public BulkPayrollController(BulkPayrollService service) {
        this.service = service;
    }

    /**
     * Pulls the current HRMS payroll data and upserts it in a single transaction.
     *
     * <p>Returns 200 whether the batch committed or rolled back — the call to this service
     * succeeded either way, and {@code committed} plus {@code failureKind} in the body say
     * what happened downstream. The only error status here is 409, when a run is already
     * under way.
     */
    @PostMapping("/bulk-upsert")
    public ResponseEntity<BulkUpsertResult> bulkUpsert(@RequestBody(required = false) BulkUpsertRequest request) {
        // The body is optional, so it arrives null when the caller sends none at all.
        BulkUpsertRequest body = request == null ? BulkUpsertRequest.empty() : request;

        BulkUpsertResult result = service.upsertAll(
                IngestionTrigger.MANUAL,
                body.periodStart(),
                body.periodEnd(),
                body.updatedSince(),
                body.strictOrDefault());

        return ResponseEntity.ok(result);
    }

    /**
     * Request body; every field is optional.
     *
     * @param periodStart  start of the pay period to pull payslips for; omit to sync workers only
     * @param periodEnd    end of that period
     * @param updatedSince incremental-sync watermark passed through to the HRMS
     * @param strict       when true, any rejected record aborts the run before anything is
     *                     written. Defaults to false: valid records are committed and the
     *                     rejections are reported.
     */
    public record BulkUpsertRequest(LocalDate periodStart,
                                    LocalDate periodEnd,
                                    LocalDate updatedSince,
                                    Boolean strict) {

        static BulkUpsertRequest empty() {
            return new BulkUpsertRequest(null, null, null, null);
        }

        boolean strictOrDefault() {
            return Boolean.TRUE.equals(strict);
        }
    }
}
