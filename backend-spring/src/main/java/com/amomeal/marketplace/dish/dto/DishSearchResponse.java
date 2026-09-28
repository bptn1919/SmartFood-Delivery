package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishSearchResponseSchema.
 * The {@code message} key only appears on the empty-query early return in
 * Django, so it is omitted when null.
 */
public record DishSearchResponse(
        String query,
        int total,
        List<DishSearchResultResponse> results,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message
) {
}
