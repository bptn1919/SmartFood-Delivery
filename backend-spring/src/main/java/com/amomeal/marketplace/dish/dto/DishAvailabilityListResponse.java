package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors ../../backend/dish/schemas/responses.py::DishAvailabilityListResponse. */
public record DishAvailabilityListResponse(
        @JsonProperty("dish_uid") String dishUid,
        @JsonProperty("dish_name") String dishName,
        List<DishAvailabilityItemResponse> availabilities
) {
}
