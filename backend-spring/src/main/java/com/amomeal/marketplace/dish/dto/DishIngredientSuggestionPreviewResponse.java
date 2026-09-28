package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors ../../backend/dish/schemas/responses.py::DishIngredientSuggestionPreviewResponse. */
public record DishIngredientSuggestionPreviewResponse(
        @JsonProperty("custom_name") String customName,
        double weight,
        NutritionValuesResponse nutrition,
        List<CandidateResponse> candidates,
        List<WarningResponse> warnings,
        double confidence
) {
}
