package com.amomeal.marketplace.cart.dto;

import java.time.LocalDate;
import java.util.List;

/** Mirrors ../../backend/cart/schemas/responses.py::DateGroupResponse. */
public record DateGroupResponse(
        LocalDate deliveryDate,
        List<ChefGroupResponse> chefs
) {
}
