package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Mirrors ../../backend/dish/schemas/responses.py::DishAvailabilityResponse. */
public record DishAvailabilityItemResponse(
        @JsonProperty("available_date") LocalDate availableDate,
        @JsonProperty("available_quantity") int availableQuantity,
        String note
) {
}
