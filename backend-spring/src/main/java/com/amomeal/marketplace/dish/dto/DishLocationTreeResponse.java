package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishLocationType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors ../../backend/dish/schemas/responses.py::DishLocationTreeResponse. */
public record DishLocationTreeResponse(
        Long id,
        String name,
        String slug,
        DishLocationType type,
        @JsonProperty("parent_id") Long parentId,
        List<DishLocationTreeResponse> children
) {
}
