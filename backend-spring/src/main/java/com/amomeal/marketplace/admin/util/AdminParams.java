package com.amomeal.marketplace.admin.util;

import com.amomeal.marketplace.admin.exception.AdminValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * Hand-rolled query-parameter parsing that mimics ninja/pydantic validation (401 VALIDATION_ERROR) so a
 * bad or missing parameter fails BEFORE the admin check, like Django. Also holds the two date parsers
 * whose failure modes differ in Django: {@code strptime} (uncaught ValueError = 500) vs pydantic
 * {@code date} (validation error = 401) vs the FilterSchema helpers (bad value silently ignored).
 */
public final class AdminParams {

    /** Python {@code strptime('%Y-%m-%d')} (accepts non-padded month/day). */
    private static final DateTimeFormatter STRPTIME = DateTimeFormatter.ofPattern("uuuu-M-d", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private AdminParams() {
    }

    public static String required(String value, String field) {
        if (value == null) {
            throw new AdminValidationException(field, "Field required");
        }
        return value;
    }

    public static int intParam(String value, String field, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AdminValidationException(field, "Input should be a valid integer, unable to parse string as an integer");
        }
    }

    /** pydantic lax bool: true/false/1/0/yes/no/on/off/t/f/y/n (case-insensitive). */
    public static Boolean boolParam(String value, String field) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on", "t", "y" -> Boolean.TRUE;
            case "false", "0", "no", "off", "f", "n" -> Boolean.FALSE;
            default -> throw new AdminValidationException(field,
                    "Input should be a valid boolean, unable to interpret input");
        };
    }

    /** pydantic {@code date} query param: strict ISO {@code YYYY-MM-DD}, else 401 validation. */
    public static LocalDate dateParam(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new AdminValidationException(field, "Input should be a valid date or datetime, invalid character in year");
        }
    }

    /** Django {@code datetime.strptime(v, '%Y-%m-%d').date()} - a failure is an UNCAUGHT ValueError (500). */
    public static LocalDate strptimeDate(String value) {
        return LocalDate.parse(value, STRPTIME);
    }

    /** FilterSchema helper: a malformed date is silently ignored ({@code Q()}), never an error. */
    public static LocalDate lenientDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, STRPTIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
