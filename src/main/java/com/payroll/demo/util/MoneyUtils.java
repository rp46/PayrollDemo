package com.payroll.demo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Money arithmetic for payroll amounts.
 *
 * <p>Every amount in this system is a {@link BigDecimal} matching the schema's
 * {@code NUMERIC(14,2)}. Two rules follow from that, and this class is where both are
 * enforced so no caller has to remember them:
 *
 * <ul>
 *   <li><b>Never round silently.</b> {@link #normalize} uses
 *       {@link RoundingMode#UNNECESSARY}, so an amount carrying more precision than the
 *       column can hold raises rather than quietly losing a fraction of someone's pay.
 *       Postgres would round it on insert without a word; the feed should be corrected
 *       instead.</li>
 *   <li><b>Never compare with equals.</b> {@code new BigDecimal("100.0")} and
 *       {@code new BigDecimal("100.00")} are the same amount but not
 *       {@code equals}, so {@link #sameValue} compares by value.</li>
 * </ul>
 */
public final class MoneyUtils {

    /** Decimal places in {@code NUMERIC(14,2)}. */
    public static final int SCALE = 2;

    /** Digits available left of the point: precision 14 minus scale 2. */
    public static final int MAX_INTEGER_DIGITS = 12;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);

    private MoneyUtils() {
    }

    /**
     * Scales an amount to the column's two decimal places.
     *
     * @throws ArithmeticException if doing so would discard a non-zero digit. Callers
     *                             should have rejected such a value already via
     *                             {@link #hasExcessPrecision}; this is the backstop.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Whether scaling to two places would lose a non-zero digit. */
    public static boolean hasExcessPrecision(BigDecimal amount) {
        return amount != null && amount.stripTrailingZeros().scale() > SCALE;
    }

    /** Whether the amount is too large for {@code NUMERIC(14,2)}. */
    public static boolean exceedsColumnWidth(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS;
    }

    public static boolean isNegative(BigDecimal amount) {
        return amount != null && amount.signum() < 0;
    }

    /** Value comparison, ignoring scale. Use this, never {@code equals}. */
    public static boolean sameValue(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    /** Sum, treating nulls as zero, at the money scale. */
    public static BigDecimal sum(Collection<BigDecimal> amounts) {
        BigDecimal total = ZERO;
        for (BigDecimal amount : amounts) {
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** {@code gross - deductions}, at the money scale. */
    public static BigDecimal subtract(BigDecimal minuend, BigDecimal subtrahend) {
        return minuend.subtract(subtrahend).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
