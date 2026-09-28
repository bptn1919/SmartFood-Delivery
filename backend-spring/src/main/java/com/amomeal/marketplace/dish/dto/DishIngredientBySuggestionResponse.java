package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/dish/schemas/responses.py::DishIngredientBySuggestionResponse. */
public record DishIngredientBySuggestionResponse(
        String status,
        @JsonProperty("dish_uid") UUID dishUid,
        @JsonProperty("ingredient_custom_name") String ingredientCustomName,
        IngredientSource source,
        @JsonProperty("suggestion_uid") UUID suggestionUid,
        @JsonProperty("approval_status") IngredientImportStatus approvalStatus,
        @JsonProperty("created_by_id") Long createdById,
        @JsonProperty("updated_by_id") Long updatedById,
        Double confidence,
        NutritionValuesResponse nutritions
) {
}
