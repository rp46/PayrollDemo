package com.payroll.demo.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilsTest {

    @Test
    void stripIsNullSafe() {
        assertThat(TextUtils.strip("  a  ")).isEqualTo("a");
        assertThat(TextUtils.strip(null)).isNull();
    }

    @Test
    @DisplayName("a blank value is absent, not an empty string")
    void blankBecomesNull() {
        assertThat(TextUtils.blankToNull("   ")).isNull();
        assertThat(TextUtils.blankToNull("")).isNull();
        assertThat(TextUtils.blankToNull(null)).isNull();
        assertThat(TextUtils.blankToNull("  ENG ")).isEqualTo("ENG");
    }

    @Test
    void truncateRespectsWidth() {
        assertThat(TextUtils.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(TextUtils.truncate("ab", 3)).isEqualTo("ab");
        assertThat(TextUtils.truncate(null, 3)).isNull();
    }

    @Test
    @DisplayName("status keys fold case, hyphens and spaces so synonyms match")
    void normaliseKeyFoldsSeparators() {
        assertThat(TextUtils.normaliseKey("on-leave")).isEqualTo("ON_LEAVE");
        assertThat(TextUtils.normaliseKey("On Leave")).isEqualTo("ON_LEAVE");
        assertThat(TextUtils.normaliseKey("  active ")).isEqualTo("ACTIVE");
        assertThat(TextUtils.normaliseKey(null)).isEmpty();
    }
}
