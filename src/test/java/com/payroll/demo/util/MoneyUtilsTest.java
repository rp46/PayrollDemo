package com.payroll.demo.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyUtilsTest {

    @Test
    @DisplayName("normalising puts an amount at the column scale without changing its value")
    void normalizeSetsScale() {
        assertThat(MoneyUtils.normalize(new BigDecimal("100"))).isEqualTo(new BigDecimal("100.00"));
        assertThat(MoneyUtils.normalize(new BigDecimal("100.5"))).isEqualTo(new BigDecimal("100.50"));
        assertThat(MoneyUtils.normalize(new BigDecimal("100.50"))).isEqualTo(new BigDecimal("100.50"));
        assertThat(MoneyUtils.normalize(null)).isNull();
    }

    @Test
    @DisplayName("trailing zeros beyond two places are dropped, since they carry no value")
    void normalizeDropsHarmlessTrailingZeros() {
        assertThat(MoneyUtils.normalize(new BigDecimal("100.500"))).isEqualTo(new BigDecimal("100.50"));
        assertThat(MoneyUtils.normalize(new BigDecimal("100.000"))).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("normalising refuses to round away a real fraction of someone's pay")
    void normalizeRefusesToRound() {
        assertThatThrownBy(() -> MoneyUtils.normalize(new BigDecimal("100.005")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("excess precision is detectable before it reaches the database")
    void detectsExcessPrecision() {
        assertThat(MoneyUtils.hasExcessPrecision(new BigDecimal("100.005"))).isTrue();
        assertThat(MoneyUtils.hasExcessPrecision(new BigDecimal("0.001"))).isTrue();

        assertThat(MoneyUtils.hasExcessPrecision(new BigDecimal("100.00"))).isFalse();
        assertThat(MoneyUtils.hasExcessPrecision(new BigDecimal("100.500"))).isFalse();
        assertThat(MoneyUtils.hasExcessPrecision(new BigDecimal("100"))).isFalse();
        assertThat(MoneyUtils.hasExcessPrecision(null)).isFalse();
    }

    @Test
    @DisplayName("an amount too wide for NUMERIC(14,2) is caught rather than overflowing at insert")
    void detectsColumnOverflow() {
        // 12 integer digits is the limit.
        assertThat(MoneyUtils.exceedsColumnWidth(new BigDecimal("999999999999.99"))).isFalse();
        assertThat(MoneyUtils.exceedsColumnWidth(new BigDecimal("1000000000000.00"))).isTrue();
        assertThat(MoneyUtils.exceedsColumnWidth(BigDecimal.ZERO)).isFalse();
        assertThat(MoneyUtils.exceedsColumnWidth(new BigDecimal("0.00"))).isFalse();
        assertThat(MoneyUtils.exceedsColumnWidth(null)).isFalse();
    }

    @Test
    @DisplayName("comparison is by value, so scale differences are not differences")
    void comparesByValueNotScale() {
        BigDecimal a = new BigDecimal("100.0");
        BigDecimal b = new BigDecimal("100.00");

        assertThat(a).isNotEqualTo(b);              // the trap this exists to avoid
        assertThat(MoneyUtils.sameValue(a, b)).isTrue();
        assertThat(MoneyUtils.sameValue(a, new BigDecimal("100.01"))).isFalse();
        assertThat(MoneyUtils.sameValue(null, null)).isTrue();
        assertThat(MoneyUtils.sameValue(a, null)).isFalse();
    }

    @Test
    void sumsAtMoneyScale() {
        assertThat(MoneyUtils.sum(List.of(new BigDecimal("10.10"), new BigDecimal("20.20"))))
                .isEqualByComparingTo("30.30");
        assertThat(MoneyUtils.sum(List.of())).isEqualByComparingTo("0.00");
        assertThat(MoneyUtils.sum(List.of()).scale()).isEqualTo(MoneyUtils.SCALE);
    }

    @Test
    @DisplayName("repeated addition stays exact, which is the reason for BigDecimal at all")
    void additionIsExact() {
        BigDecimal total = MoneyUtils.ZERO;
        for (int i = 0; i < 10; i++) {
            total = total.add(new BigDecimal("0.10"));
        }
        assertThat(total).isEqualByComparingTo("1.00");

        // The same loop in binary floating point does not land on 1.0.
        double drifting = 0.0;
        for (int i = 0; i < 10; i++) {
            drifting += 0.10;
        }
        assertThat(drifting).isNotEqualTo(1.0);
    }

    @Test
    void subtractsAtMoneyScale() {
        assertThat(MoneyUtils.subtract(new BigDecimal("100.00"), new BigDecimal("18.00")))
                .isEqualByComparingTo("82.00");
    }

    @Test
    void detectsNegativeAmounts() {
        assertThat(MoneyUtils.isNegative(new BigDecimal("-0.01"))).isTrue();
        assertThat(MoneyUtils.isNegative(BigDecimal.ZERO)).isFalse();
        assertThat(MoneyUtils.isNegative(null)).isFalse();
    }
}
