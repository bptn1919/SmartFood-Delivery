package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/** Mirrors ../../backend/dish/schemas/responses.py::DishIngredientPreviewResponse. */
public record DishIngredientPreviewResponse(
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("ingredient_name") String ingredientName,
        double weight,
        NutritionValuesResponse nutritions,
        List<WarningResponse> warnings,
        double confidence
) {
}
