package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishLocationType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Mirrors ../../backend/dish/schemas/requests.py::DishLocationCreateSchema. */
public record DishLocationCreateRequest(
        @NotBlank String name,
        @NotNull DishLocationType type,
        @JsonProperty("parent_id") Long parentId
) {
}
