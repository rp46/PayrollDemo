package com.payroll.demo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The few entity methods that do more than get and set: the ones that keep both sides of a
 * bidirectional association in step, and the truncation that keeps audit text in its column.
 */
class DomainModelTest {

    @Test
    @DisplayName("adding a line sets the back-reference, so a save cascades correctly")
    void payslipAddLineLinksBothSides() {
        Payslip payslip = new Payslip();
        PayslipLine line = new PayslipLine();
        line.setComponentCode("BASIC");
        line.setComponentType(ComponentType.EARNING);
        line.setAmount(new BigDecimal("100.00"));

        payslip.addLine(line);

        assertThat(payslip.getLines()).containsExactly(line);
        assertThat(line.getPayslip())
                .as("without this the insert would fail on a null payslip_id")
                .isSameAs(payslip);
    }

    @Test
    void payslipStartsWithNoLines() {
        assertThat(new Payslip().getLines()).isEmpty();
    }

    @Test
    @DisplayName("adding an error sets the back-reference the same way")
    void ingestionRunAddErrorLinksBothSides() {
        IngestionRun run = new IngestionRun();
        IngestionRunError error = IngestionRunError.of(IngestionRecordType.WORKER, "W-1", "bad salary");

        run.addError(error);

        assertThat(run.getErrors()).containsExactly(error);
        assertThat(error.getRun()).isSameAs(run);
    }

    @Test
    void ingestionRunStartsEmptyAndRunning() {
        IngestionRun run = new IngestionRun();
        assertThat(run.getErrors()).isEmpty();
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.RUNNING);
    }

    @Test
    @DisplayName("an over-long reason is cut to the column width rather than failing the insert")
    void runErrorTruncatesLongText() {
        IngestionRunError error = IngestionRunError.of(
                IngestionRecordType.PAYSLIP, "X".repeat(200), "y".repeat(900));

        assertThat(error.getExternalId()).hasSize(100);
        assertThat(error.getReason()).hasSize(IngestionRunError.MAX_REASON_LENGTH);
    }

    @Test
    void runErrorKeepsShortTextIntact() {
        IngestionRunError error = IngestionRunError.of(IngestionRecordType.WORKER, "W-1", "short reason");

        assertThat(error.getExternalId()).isEqualTo("W-1");
        assertThat(error.getReason()).isEqualTo("short reason");
        assertThat(error.getRecordType()).isEqualTo(IngestionRecordType.WORKER);
    }

    @Test
    @DisplayName("a record with no id and no reason still produces a storable row")
    void runErrorHandlesMissingValues() {
        IngestionRunError error = IngestionRunError.of(IngestionRecordType.WORKER, null, null);

        assertThat(error.getExternalId()).isNull();
        assertThat(error.getReason())
                .as("the reason column is NOT NULL, so something must be written")
                .isEqualTo("unspecified");
    }
}
