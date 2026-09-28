package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.dish.service.DishStatsProvider;
import com.amomeal.marketplace.order.repository.OrderItemRepository;
import com.amomeal.marketplace.review.service.ReviewStatsProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code order} half of {@code dish}'s {@link DishStatsProvider} seam: real
 * {@code sold_count} (SUM of OrderItem.quantity over COMPLETED orders, Django's
 * Subquery annotation in dish/orm/dish.py).
 *
 * <p><b>UPDATE (review port, 2026-09-23):</b> the {@code review} half
 * ({@code review_count}, platform-average rating) is now real too, delegated to
 * {@link ReviewStatsProvider} (implemented in the `review` module,
 * {@code ReviewStatsProviderImpl}, backed by the real {@code review} table).
 * This is the ONE sanctioned edit to `order`'s main source for the `review`
 * port (adding this one constructor dependency + two delegating method
 * bodies) — this class's own prior javadoc explicitly pre-approved exactly
 * this change ("fill in the two review methods here via a small review-owned
 * query component") to avoid a second {@code @Primary DishStatsProvider} bean,
 * which would be an ambiguous-bean startup failure. Nothing else in `order`
 * was touched for the `review` port.
 */
@Primary
@Component
@RequiredArgsConstructor
public class OrderDishStatsProvider implements DishStatsProvider {

    private final OrderItemRepository orderItemRepository;
    private final ReviewStatsProvider reviewStatsProvider;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> soldCountByDish(Collection<UUID> dishUids) {
        return orderItemRepository.soldCountMap(dishUids);
    }

    @Override
    public Map<UUID, Long> reviewCountByDish(Collection<UUID> dishUids) {
        return reviewStatsProvider.reviewCountByDish(dishUids);
    }

    @Override
    public double systemAverageRating() {
        return reviewStatsProvider.systemAverageRating();
    }
}
