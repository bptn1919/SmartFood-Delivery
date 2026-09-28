package com.amomeal.marketplace.cart.dto;

import java.util.List;

/** Mirrors ../../backend/cart/schemas/responses.py::CartResponse. */
public record CartResponse(
        List<DateGroupResponse> items,
        double totalAmount,
        String message
) {
}
