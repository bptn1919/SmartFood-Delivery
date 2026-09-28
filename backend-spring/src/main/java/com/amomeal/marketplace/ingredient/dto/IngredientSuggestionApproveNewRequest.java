package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors ../../backend/ingredient/schemas/requests.py::IngredientSuggestionApproveNewSchema. */
public record IngredientSuggestionApproveNewRequest(
        @JsonProperty("resolution_note") String resolutionNote
) {
}
