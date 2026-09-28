package com.amomeal.marketplace.cart.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Mirrors ../../backend/cart/schemas/requests.py::CartSetQuantityRequest. */
public record CartSetQuantityRequest(
        @NotNull LocalDate deliveryDate,
        @NotNull Integer targetQuantity
) {
}
