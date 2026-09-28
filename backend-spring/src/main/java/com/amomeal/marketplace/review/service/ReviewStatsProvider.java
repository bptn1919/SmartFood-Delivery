package com.amomeal.marketplace.review.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code review} half of {@code dish}'s {@code DishStatsProvider} seam
 * (see {@code com.amomeal.marketplace.dish.service.DishStatsProvider}'s
 * javadoc) — {@code review_count} per dish and the platform-wide average
 * rating the Bayesian top-dish ranking shrinks toward.
 *
 * <p>Consumed by {@code order.service.OrderDishStatsProvider}, which already
 * owns the {@code @Primary DishStatsProvider} bean for the {@code sold_count}
 * half and was written with this exact extension point in mind (see its
 * javadoc: "fill in the two review methods here via a small review-owned
 * query component, or replace this class with one combined provider" — this
 * interface + {@link ReviewStatsProviderImpl} is that "small review-owned
 * query component"). Injecting this one extra dependency into
 * {@code OrderDishStatsProvider} is the only edit made to the `order` module's
 * main source for this port (see PROGRESS.md review row) — everything else
 * about `order`/`dish` is untouched. A second {@code @Primary
 * DishStatsProvider} bean was deliberately NOT added: two {@code @Primary}
 * beans of the same interface is an ambiguous-bean startup failure, and
 * `OrderDishStatsProvider`'s own javadoc already warned against it.
 */
public interface ReviewStatsProvider {

    /** Django: the {@code review_count} Subquery annotation, COALESCEd to 0. */
    Map<UUID, Long> reviewCountByDish(Collection<UUID> dishUids);

    /** Django: {@code Review.objects.filter(deleted=False).aggregate(Avg("rating")) or 0.0}. */
    double systemAverageRating();
}
