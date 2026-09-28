package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishLocation;
import com.amomeal.marketplace.dish.entity.DishLocationType;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors ../../backend/dish/schemas/responses.py::DishLocationResponse. */
public record DishLocationResponse(
        Long id,
        String name,
        String slug,
        DishLocationType type,
        @JsonProperty("parent_id") Long parentId
) {
    public static DishLocationResponse from(DishLocation location) {
        return new DishLocationResponse(
                location.getId(),
                location.getName(),
                location.getSlug(),
                location.getType(),
                location.getParent() == null ? null : location.getParent().getId());
    }
}
