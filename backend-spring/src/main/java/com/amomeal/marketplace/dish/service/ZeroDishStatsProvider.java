package com.amomeal.marketplace.dish.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link DishStatsProvider} for as long as `order` and `review` are not
 * ported: every dish has 0 completed sales and 0 reviews, and the platform-wide
 * average rating is 0.0 — byte-for-byte what Django returns on an empty
 * OrderItem/Review table (its subqueries are COALESCEd to 0 and its
 * {@code Avg("rating")} aggregate falls back to {@code or 0.0}).
 *
 * <p>Drops out automatically as soon as one of those modules registers a real
 * land.
 *
 * <p><b>How this gets replaced:</b> {@code @ConditionalOnMissingBean} is NOT used
 * here on purpose - it is only evaluated for auto-configuration {@code @Bean}
 * methods, never for component-scanned {@code @Component}s (using it that way
 * silently never fires, and broke this application context once already). The
 * module that takes ownership therefore annotates its own implementation
 * {@code @Primary}, and this default quietly steps aside.
 */
@Component
public class ZeroDishStatsProvider implements DishStatsProvider {

    @Override
    public Map<UUID, Long> soldCountByDish(Collection<UUID> dishUids) {
        return Map.of();
    }

    @Override
    public Map<UUID, Long> reviewCountByDish(Collection<UUID> dishUids) {
        return Map.of();
    }

    @Override
    public double systemAverageRating() {
        return 0.0;
    }
}
