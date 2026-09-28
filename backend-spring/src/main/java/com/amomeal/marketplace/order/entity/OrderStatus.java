package com.amomeal.marketplace.order.entity;

/**
 * Mirrors ../../backend/utils/enums.py::OrderStatusEnum, including its docstring:
 *
 * <ul>
 *   <li>{@code DRAFT} — order created (from a checkout), not yet placed</li>
 *   <li>{@code PENDING} — placed; for COD this means "waiting for the chef", for
 *       PayOS "waiting for the payment webhook"</li>
 *   <li>{@code CONFIRMED_SYSTEM} — PayOS payment succeeded, auto-confirmed by the system</li>
 *   <li>{@code CONFIRMED_SHOP} — the chef manually accepted the order</li>
 *   <li>{@code PROCESSING} — the chef is cooking</li>
 *   <li>{@code DELIVERING} — out for delivery</li>
 *   <li>{@code COMPLETED} — delivered (terminal)</li>
 *   <li>{@code CANCELLED} — cancelled by customer/chef/sweep (terminal)</li>
 * </ul>
 */
public enum OrderStatus {
    DRAFT,
    PENDING,
    CONFIRMED_SYSTEM,
    CONFIRMED_SHOP,
    PROCESSING,
    DELIVERING,
    COMPLETED,
    CANCELLED
}
