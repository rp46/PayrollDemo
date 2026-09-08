package com.payroll.demo.util;

import java.util.Locale;

/**
 * Null-safe string handling shared by both ingestion paths.
 *
 * <p>Small, but the alternative is the same four idioms rewritten in five classes, each
 * with its own opinion on whether a blank string is a value.
 */
public final class TextUtils {

    private TextUtils() {
    }

    /** Strips surrounding whitespace; {@code null} in, {@code null} out. */
    public static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** Strips, then treats an empty result as absent. */
    public static String blankToNull(String value) {
        String stripped = strip(value);
        return (stripped == null || stripped.isEmpty()) ? null : stripped;
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Cuts a value to a column width. Used to keep audit text inside its column. */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * Canonical form for matching a code against a synonym set: upper case, hyphens and
     * spaces folded to underscores. Locale-independent so a Turkish default locale cannot
     * turn "i" into a dotless one and break status matching.
     */
    public static String normaliseKey(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
