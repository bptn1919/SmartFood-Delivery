package com.amomeal.marketplace.order.dto;

import java.util.List;

/**
 * Mirrors ../../backend/order/schemas/responses.py::OrderListResponse.
 *
 * <p>PORT-NOTE: Django's {@code get_my_orders}/{@code get_customer_orders} wrap
 * <b>exactly one</b> order per element ("Array chỉ có 1 phần tử" in the source
 * comment) — the grouping-by-chef version is commented out right above it. The
 * envelope shape is kept so the FE still receives {@code List[OrderListResponse]},
 * but it is one wrapper per order, not one per chef.
 */
public record OrderListResponse(ChefInfoResponse chefInfo, List<OrderResponseWithInfo> orders) {
}
