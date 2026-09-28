package com.amomeal.marketplace.admin.util;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/** Small helpers shared by the admin specifications / queries. */
public final class Specs {

    private static final char ESC = '!';

    private Specs() {
    }

    /** Django {@code __icontains}: case-insensitive substring with LIKE wildcards escaped. */
    public static Predicate icontains(CriteriaBuilder cb, Expression<String> path, String value) {
        String escaped = value.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return cb.like(cb.lower(path), "%" + escaped + "%", ESC);
    }

    /** Django {@code created_at__date__gte=d} with USE_TZ + TIME_ZONE=UTC: start of that UTC day. */
    public static Instant startOfDay(LocalDate d) {
        return d.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** Django {@code created_at__date__lte=d}: strictly before the start of the next UTC day. */
    public static Instant startOfNextDay(LocalDate d) {
        return d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
