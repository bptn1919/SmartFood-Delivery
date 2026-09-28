package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishLocationType;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors ../../backend/dish/schemas/requests.py::DishLocationUpdateSchema. */
public record DishLocationUpdateRequest(
        String name,
        DishLocationType type,
        @JsonProperty("parent_id") Long parentId
) {
}
