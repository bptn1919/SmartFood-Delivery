package com.amomeal.marketplace.cart.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Mirrors ../../backend/cart/schemas/requests.py::CartAddRequest. Plain
 * camelCase fields map to snake_case JSON automatically (CLAUDE.md §3) — no
 * {@code @JsonProperty} needed.
 */
public record CartAddRequest(
        @NotNull UUID dishUid,
        @NotNull LocalDate deliveryDate,
        @NotNull Integer quantityToAdd
) {
}
