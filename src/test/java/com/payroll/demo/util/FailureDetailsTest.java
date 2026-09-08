package com.payroll.demo.util;

import com.payroll.demo.domain.IngestionFailureKind;
import com.payroll.demo.hrms.HrmsApiException;
import com.payroll.demo.hrms.HrmsFailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureDetailsTest {

    @Test
    @DisplayName("an upstream failure keeps its kind and its diagnostic body")
    void classifiesHrmsFailures() {
        var failure = FailureDetails.classify(
                new HrmsApiException(HrmsFailureKind.BAD_REQUEST, "HTTP 400", 400, "{\"error\":\"bad\"}", null, null),
                "irrelevant");

        assertThat(failure.kind()).isEqualTo(IngestionFailureKind.BAD_REQUEST);
        assertThat(failure.detail()).contains("BAD_REQUEST").contains("bad");
    }

    @Test
    @DisplayName("a database failure is ours, and the caller supplies the wording")
    void classifiesDatabaseFailures() {
        var failure = FailureDetails.classify(
                new DataIntegrityViolationException("employees_base_salary_chk"), "batch rolled back");

        assertThat(failure.kind()).isEqualTo(IngestionFailureKind.INTERNAL_ERROR);
        assertThat(failure.detail()).startsWith("batch rolled back: ").contains("employees_base_salary_chk");
    }

    @Test
    void classifiesAnythingElseAsInternal() {
        var failure = FailureDetails.classify(new IllegalStateException("boom"), "db");

        assertThat(failure.kind()).isEqualTo(IngestionFailureKind.INTERNAL_ERROR);
        assertThat(failure.detail()).isEqualTo("IllegalStateException: boom");
    }
}
