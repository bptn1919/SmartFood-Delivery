package com.amomeal.marketplace.dish.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The two per-dish aggregates that Django computes with {@code Subquery(...)}
 * annotations over tables this port does not own:
 * <ul>
 *   <li>{@code sold_count} = SUM(OrderItem.quantity) where the parent Order is
 *       COMPLETED (the `order` app);</li>
 *   <li>{@code review_count} = COUNT(Review) where deleted=False, plus the
 *       platform-wide AVG(rating) the Bayesian ranking shrinks toward (the
 *       `review` app).</li>
 * </ul>
 *
 * <p><b>Why an interface instead of a stub module:</b> `order` and `review` are
 * not ported yet (CLAUDE.md §7 puts them after `dish`), so their tables do not
 * exist and querying them is impossible. Rather than hardcode zeros deep inside
 * {@link DishService} — which would then have to be hunted down and rewritten
 * twice later — this is the single, explicit seam where those modules plug in:
 * each will contribute its own {@code @Component} implementation (or one
 * combined one) and {@link ZeroDishStatsProvider} will step aside via
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Until then the ranking formula itself
 * ({@link TopDishRanking#bayesianScore}) is fully real and fully tested — only
 * its inputs are zero, which is exactly what Django itself returns for a
 * platform with no completed orders and no reviews.
 */
public interface DishStatsProvider {

    /** Django: the {@code sold_count} subquery, COALESCEd to 0. */
    Map<UUID, Long> soldCountByDish(Collection<UUID> dishUids);

    /** Django: the {@code review_count} subquery, COALESCEd to 0. */
    Map<UUID, Long> reviewCountByDish(Collection<UUID> dishUids);

    /** Django: {@code Review.objects.filter(deleted=False).aggregate(Avg("rating")) or 0.0}. */
    double systemAverageRating();

    default long soldCount(Map<UUID, Long> counts, UUID dishUid) {
        return counts.getOrDefault(dishUid, 0L);
    }
}
