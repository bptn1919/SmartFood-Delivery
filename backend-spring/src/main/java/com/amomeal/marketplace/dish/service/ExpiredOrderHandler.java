package com.amomeal.marketplace.dish.service;

import java.util.Set;
import java.util.UUID;

/**
 * Steps 2 and 3 of ../../backend/dish/tasks.py::release_expired_stock_holds:
 * cancel the DRAFT/PENDING orders whose stock holds just expired, expire their
 * reserved/used vouchers, and enqueue the CANCELLED_EXPIRED customer
 * notification.
 *
 * <p>All of that reads and writes `order` and `voucher` tables, which do not
 * exist yet (CLAUDE.md §7 ports both after `dish`). Rather than let the dish
 * module grow half an order-cancellation state machine, the sweep calls through
 * this one interface; the `order` port contributes the real implementation and
 * {@link LoggingExpiredOrderHandler} steps aside.
 */
public interface ExpiredOrderHandler {

    /**
     * @param orderUids orders whose stock holds the sweep just expired
     * @return how many were actually transitioned to CANCELLED
     */
    int cancelExpiredOrders(Set<UUID> orderUids);
}
