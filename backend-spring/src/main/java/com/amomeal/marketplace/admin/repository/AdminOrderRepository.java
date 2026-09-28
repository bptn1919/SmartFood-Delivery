package com.amomeal.marketplace.admin.repository;

import com.amomeal.marketplace.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Admin-owned read view over order's {@link Order} entity (filtered, paginated admin list). */
public interface AdminOrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {
}
