package com.amomeal.marketplace.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Mirrors ../../backend/order/schemas/responses.py::OrderItemResponse. */
public record OrderItemResponse(
        UUID dishUid,
        String dishName,
        String chefName,
        String imageUrl,
        int quantity,
        BigDecimal price,
        BigDecimal subtotal) {
}
