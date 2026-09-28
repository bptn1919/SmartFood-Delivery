package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Mirrors ../../backend/dish/schemas/requests.py::DishAvailabilitySchema (a
 * ModelSchema over DishAvailability excluding id/dish).
 */
public record DishAvailabilityRequest(
        @JsonProperty("available_date") @NotNull LocalDate availableDate,
        @JsonProperty("is_available") Boolean available,
        @JsonProperty("available_quantity") Integer availableQuantity,
        String note
) {
}
