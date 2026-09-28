package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishLocation;

/** Mirrors ../../backend/dish/schemas/responses.py::DishLocationShortResponse. */
public record DishLocationShortResponse(Long id, String name) {
    public static DishLocationShortResponse from(DishLocation location) {
        return new DishLocationShortResponse(location.getId(), location.getName());
    }
}
