package com.amomeal.marketplace.report.repository;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * report-owned read-only view over order's entities (same pattern as payment's PaymentOrderRepository):
 * the completed-order counts of report/analysis.py, without editing order's repositories.
 */
public interface ReportOrderRepository extends Repository<Order, UUID> {

    @Query("select count(o) from Order o where o.chef.id = :chefId and o.status = :status and o.createdAt >= :cutoff")
    long countByChef(@Param("chefId") Long chefId, @Param("status") OrderStatus status, @Param("cutoff") Instant cutoff);

    /** Distinct COMPLETED orders in the window that contain the dish. */
    @Query("select count(distinct i.order.uid) from OrderItem i where i.dish.uid = :dishUid "
            + "and i.order.chef.id = :chefId and i.order.status = :status and i.order.createdAt >= :cutoff")
    long countOrdersWithDish(@Param("dishUid") UUID dishUid, @Param("chefId") Long chefId,
                             @Param("status") OrderStatus status, @Param("cutoff") Instant cutoff);
}
