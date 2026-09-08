package com.payroll.demo.dto;

import com.payroll.demo.domain.ComponentType;
import com.payroll.demo.domain.EmployeeStatus;
import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.domain.IngestionRecordType;
import com.payroll.demo.domain.IngestionRunError;
import com.payroll.demo.domain.IngestionStatus;
import com.payroll.demo.dto.HrmsDtos.HrmsPage;
import com.payroll.demo.dto.HrmsDtos.HrmsPayslip;
import com.payroll.demo.dto.PayrollRows.DepartmentRow;
import com.payroll.demo.dto.PayrollRows.EmployeeRow;
import com.payroll.demo.dto.PayrollRows.PayPeriodRow;
import com.payroll.demo.dto.PayrollRows.PayrollBatch;
import com.payroll.demo.dto.PayrollRows.PayslipKey;
import com.payroll.demo.dto.PayrollRows.PayslipLineRow;
import com.payroll.demo.dto.PayrollRows.PayslipRow;
import com.payroll.demo.dto.PayrollRows.PeriodKey;
import com.payroll.demo.service.RunTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The records that carry logic rather than just fields.
 */
class PayrollDtosLogicTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    // ------------------------------------------------------------- HrmsPage

    @Test
    @DisplayName("an explicit hasMore always wins over the page-size heuristic")
    void explicitHasMoreWins() {
        assertThat(new HrmsPage<>(List.of("a", "b"), 0, 2, 2L, true).moreAvailable(2)).isTrue();
        assertThat(new HrmsPage<>(List.of("a", "b"), 0, 2, 2L, false).moreAvailable(2))
                .as("a full page still means no more when the feed says so")
                .isFalse();
    }

    @Test
    @DisplayName("without hasMore, a full page is taken to mean another follows")
    void inferredPaging() {
        assertThat(new HrmsPage<>(List.of("a", "b"), 0, 2, null, null).moreAvailable(2)).isTrue();
        assertThat(new HrmsPage<>(List.of("a"), 0, 1, null, null).moreAvailable(2)).isFalse();
        assertThat(new HrmsPage<>(List.of(), 0, 0, null, null).moreAvailable(2))
                .as("an empty page never means there is more")
                .isFalse();
    }

    @Test
    @DisplayName("a null item list reads as empty rather than exploding")
    void nullItemsAreSafe() {
        HrmsPage<String> page = new HrmsPage<>(null, null, null, null, null);
        assertThat(page.safeItems()).isEmpty();
        assertThat(page.moreAvailable(10)).isFalse();
    }

    @Test
    void nullPayslipLinesAreSafe() {
        HrmsPayslip slip = new HrmsPayslip("PS-1", "E-1", START, END, null,
                null, null, null, null, null);
        assertThat(slip.safeLines()).isEmpty();
    }

    // ---------------------------------------------------------- PayrollBatch

    @Test
    void emptyBatchIsRecognised() {
        assertThat(new PayrollBatch(List.of(), List.of(), List.of(), List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a batch with anything at all in it is not empty")
    void anyContentMakesTheBatchNonEmpty() {
        DepartmentRow dept = new DepartmentRow("ENG", "Engineering");
        EmployeeRow emp = new EmployeeRow("E-1", "A", "B", "a@example.com",
                START, null, EmployeeStatus.ACTIVE, "ENG", BigDecimal.TEN);
        PayPeriodRow period = new PayPeriodRow(START, END, END);
        PayslipRow slip = new PayslipRow(new PayslipKey("E-1", START, END),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, List.of());

        assertThat(new PayrollBatch(List.of(dept), List.of(), List.of(), List.of()).isEmpty()).isFalse();
        assertThat(new PayrollBatch(List.of(), List.of(emp), List.of(), List.of()).isEmpty()).isFalse();
        assertThat(new PayrollBatch(List.of(), List.of(), List.of(period), List.of()).isEmpty()).isFalse();
        assertThat(new PayrollBatch(List.of(), List.of(), List.of(), List.of(slip)).isEmpty()).isFalse();
    }

    @Test
    void countsLinesAcrossEveryPayslip() {
        PayslipLineRow line = new PayslipLineRow("BASIC", ComponentType.EARNING, "Basic", BigDecimal.TEN);
        PayslipRow twoLines = new PayslipRow(new PayslipKey("E-1", START, END),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, List.of(line, line));
        PayslipRow noLines = new PayslipRow(new PayslipKey("E-2", START, END),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, List.of());

        assertThat(new PayrollBatch(List.of(), List.of(), List.of(), List.of(twoLines, noLines)).totalLines())
                .isEqualTo(2);
        assertThat(new PayrollBatch(List.of(), List.of(), List.of(), List.of()).totalLines()).isZero();
    }

    @Test
    void payPeriodRowExposesItsNaturalKey() {
        assertThat(new PayPeriodRow(START, END, END).key()).isEqualTo(new PeriodKey(START, END));
    }

    // ------------------------------------------------------ BulkUpsertResult

    @Test
    @DisplayName("an applied result reports the counts and carries the rejections")
    void appliedResultCarriesCountsAndRejections() {
        RunTally tally = new RunTally();
        tally.countEmployeesFetched(4);
        tally.recordWritten(3, 2);
        tally.reject(IngestionRecordType.WORKER, "W-4", "negative salary");

        BulkUpsertResult result = BulkUpsertResult.applied(
                7L, IngestionStatus.PARTIAL, new BulkWriteCounts(1, 3, 0, 2, 5), tally);

        assertThat(result.committed()).isTrue();
        assertThat(result.written().employees()).isEqualTo(3);
        assertThat(result.recordsRejected()).isEqualTo(1);
        assertThat(result.rejectedRecords()).singleElement().satisfies(r -> {
            assertThat(r.recordType()).isEqualTo(IngestionRecordType.WORKER);
            assertThat(r.externalId()).isEqualTo("W-4");
            assertThat(r.reason()).isEqualTo("negative salary");
        });
        assertThat(result.failureKind()).isNull();
    }

    @Test
    @DisplayName("a strict-mode rejection reports nothing written")
    void rejectedResultWritesNothing() {
        RunTally tally = new RunTally();
        tally.reject(IngestionRecordType.PAYSLIP, "PS-9", "does not balance");

        BulkUpsertResult result = BulkUpsertResult.rejected(7L, tally);

        assertThat(result.committed()).isFalse();
        assertThat(result.written()).isNull();
        assertThat(result.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(result.failureDetail()).contains("nothing was written");
    }

    @Test
    void failedResultCarriesTheKindAndDetail() {
        BulkUpsertResult result = BulkUpsertResult.failed(
                7L, IngestionFailureKind.TIMEOUT, "timed out", new RunTally());

        assertThat(result.committed()).isFalse();
        assertThat(result.written()).isNull();
        assertThat(result.failureKind()).isEqualTo(IngestionFailureKind.TIMEOUT);
        assertThat(result.rejectedRecords()).isEmpty();
    }

    @Test
    void writeCountsRenderReadably() {
        assertThat(new BulkWriteCounts(1, 2, 3, 4, 5))
                .hasToString("1 department(s), 2 employee(s), 3 pay period(s), 4 payslip(s), 5 line(s)");
        assertThat(BulkWriteCounts.none().employees()).isZero();
    }

    // ------------------------------------------------------------- ApiError

    @Test
    void apiErrorStampsATimestamp() {
        ApiError error = ApiError.of(404, "NOT_FOUND", "nope", "/api/x");

        assertThat(error.timestamp()).isNotNull();
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.error()).isEqualTo("NOT_FOUND");
        assertThat(error.path()).isEqualTo("/api/x");
    }

    // -------------------------------------------------------------- RunTally

    @Test
    @DisplayName("the stored error list is capped but the count keeps rising")
    void tallyCapsStoredErrors() {
        RunTally tally = new RunTally();
        for (int i = 0; i < RunTally.MAX_RECORDED_ERRORS + 25; i++) {
            tally.reject(IngestionRecordType.WORKER, "W-" + i, "bad");
        }

        assertThat(tally.getRecordsRejected()).isEqualTo(RunTally.MAX_RECORDED_ERRORS + 25);
        assertThat(tally.getErrors()).hasSize(RunTally.MAX_RECORDED_ERRORS);
        assertThat(tally.hasRejections()).isTrue();
    }

    @Test
    void aCleanTallyHasNoRejections() {
        RunTally tally = new RunTally();
        assertThat(tally.hasRejections()).isFalse();
        assertThat(tally.getErrors()).isEmpty();
        assertThat(tally.getEmployeesFetched()).isZero();
        assertThat(tally.getHttpAttempts()).isZero();
    }

    @Test
    @DisplayName("the returned error list is a copy, so a caller cannot mutate the tally")
    void tallyErrorsAreDefensivelyCopied() {
        RunTally tally = new RunTally();
        tally.reject(IngestionRecordType.WORKER, "W-1", "bad");

        List<IngestionRunError> errors = tally.getErrors();

        assertThatThrownBy(errors::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(tally.getErrors()).hasSize(1);
    }
}
