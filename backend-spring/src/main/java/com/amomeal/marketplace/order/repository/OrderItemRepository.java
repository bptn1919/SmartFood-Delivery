package com.amomeal.marketplace.order.repository;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrder(Order order);

    /** Django: {@code OrderORM.check_dish_in_order}. */
    @Query("select count(i) > 0 from OrderItem i where i.order.uid = :orderUid and i.dish.uid = :dishUid")
    boolean existsByOrderUidAndDishUid(@Param("orderUid") UUID orderUid, @Param("dishUid") UUID dishUid);

    /**
     * Django: the {@code sold_count} Subquery annotation in {@code dish/orm/dish.py} —
     * {@code SUM(OrderItem.quantity)} restricted to COMPLETED parent orders. Backs
     * {@code order.service.OrderDishStatsProvider}, the {@code order} half of
     * {@code dish}'s {@code DishStatsProvider} seam.
     */
    @Query("""
            select i.dish.uid as dishUid, coalesce(sum(i.quantity), 0) as total
            from OrderItem i
            where i.dish.uid in :dishUids
              and i.order.status = com.amomeal.marketplace.order.entity.OrderStatus.COMPLETED
            group by i.dish.uid
            """)
    List<DishSoldCount> sumCompletedQuantityByDish(@Param("dishUids") Collection<UUID> dishUids);

    interface DishSoldCount {
        UUID getDishUid();

        Long getTotal();
    }

    /** Convenience: collapse {@link #sumCompletedQuantityByDish} into a map. */
    default Map<UUID, Long> soldCountMap(Collection<UUID> dishUids) {
        if (dishUids == null || dishUids.isEmpty()) {
            return Map.of();
        }
        return sumCompletedQuantityByDish(dishUids).stream()
                .collect(java.util.stream.Collectors.toMap(DishSoldCount::getDishUid, DishSoldCount::getTotal));
    }
}
