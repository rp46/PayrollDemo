package com.payroll.demo.domain;

import com.payroll.demo.hrms.HrmsFailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionFailureKindTest {

    @ParameterizedTest
    @EnumSource(HrmsFailureKind.class)
    @DisplayName("every upstream kind maps to a kind of the same name")
    void everyUpstreamKindIsTranslated(HrmsFailureKind upstream) {
        IngestionFailureKind mapped = IngestionFailureKind.from(upstream);

        assertThat(mapped).isNotNull();
        assertThat(mapped.name())
                .as("the two enums are kept name-for-name so the audit row reads the same as the log")
                .isEqualTo(upstream.name());
    }

    @Test
    @DisplayName("INTERNAL_ERROR has no upstream counterpart; it is the one that is ours")
    void internalErrorIsNotAnUpstreamKind() {
        assertThat(IngestionFailureKind.valueOf("INTERNAL_ERROR")).isNotNull();

        for (HrmsFailureKind upstream : HrmsFailureKind.values()) {
            assertThat(IngestionFailureKind.from(upstream))
                    .isNotEqualTo(IngestionFailureKind.INTERNAL_ERROR);
        }
    }

    @Test
    @DisplayName("the mapping covers the enum, so a new upstream kind cannot slip through untranslated")
    void mappingIsExhaustive() {
        assertThat(IngestionFailureKind.values())
                .hasSize(HrmsFailureKind.values().length + 1);
    }
}
