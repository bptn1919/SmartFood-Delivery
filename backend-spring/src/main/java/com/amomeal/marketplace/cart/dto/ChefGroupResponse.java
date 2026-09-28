package com.amomeal.marketplace.cart.dto;

import java.util.List;

/** Mirrors ../../backend/cart/schemas/responses.py::ChefGroupResponse. */
public record ChefGroupResponse(
        String chefName,
        List<CartItemResponse> items
) {
}
