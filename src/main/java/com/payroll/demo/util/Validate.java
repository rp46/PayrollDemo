package com.payroll.demo.util;


import com.payroll.demo.exception.ApiException;
import java.time.LocalDate;

/**
 * Input checks for the service tier.
 *
 * <p>These run before anything else so a bad argument is refused with a sentence the
 * caller can act on, instead of surfacing later as a {@code NullPointerException} from
 * somewhere in the middle of a query. Catching it after the fact still produces a 400, but
 * a useless one: "a required value was missing" says far less than "employeeCode is required".
 */
public final class Validate {

    private Validate() {
    }

    /** @return the trimmed value */
    public static String requireText(String value, String field) {
        if (TextUtils.isBlank(value)) {
            throw ApiException.badRequest(field + " is required");
        }
        return value.strip();
    }

    /** @return the trimmed value, or null if absent */
    public static String optionalText(String value, String field, int maxLength) {
        String stripped = TextUtils.blankToNull(value);
        if (stripped != null && stripped.length() > maxLength) {
            throw ApiException.badRequest(
                    field + " must be at most " + maxLength + " characters");
        }
        return stripped;
    }

    public static Long requireId(Long value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " is required");
        }
        if (value <= 0) {
            throw ApiException.badRequest(field + " must be a positive id, but was " + value);
        }
        return value;
    }

    /**
     * Checks a date window.
     *
     * <p>Both bounds are optional, but an inverted range is always a mistake: it silently
     * matches nothing, so the caller would get an empty result and no reason for it.
     */
    public static void requireValidRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest(
                    "'to' (" + to + ") must not be before 'from' (" + from + ")");
        }
    }
}
