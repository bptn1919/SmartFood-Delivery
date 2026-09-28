package com.amomeal.marketplace.cart.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Mirrors ../../backend/cart/schemas/requests.py::CartRemoveRequest. */
public record CartRemoveRequest(
        @NotNull LocalDate deliveryDate
) {
}
