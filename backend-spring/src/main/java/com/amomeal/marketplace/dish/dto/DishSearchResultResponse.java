package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors ../../backend/dish/schemas/responses.py::DishSearchResultSchema. */
public record DishSearchResultResponse(
        String uid,
        String name,
        String category,
        double price,
        String description,
        String status,
        double rating,
        @JsonProperty("popularity_score") double popularityScore,
        @JsonProperty("search_score") double searchScore,
        @JsonProperty("image_url") String imageUrl,
        @JsonProperty("location_id") Long locationId,
        @JsonProperty("is_favorite") boolean favorite
) {
}
