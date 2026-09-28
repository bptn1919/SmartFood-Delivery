package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishIngredientSaveResponse.
 *
 * <p>PORT-NOTE on the {@code status} field: for add_ingredient_to_dish Django
 * puts the DishIngredient approval_status there
 * ({@code "status": dish_ingredient.approval_status}), while
 * update_dish_ingredient puts the literal string {@code "success"}. Both are
 * preserved verbatim, which is why this is a plain String rather than an enum.
 */
public record DishIngredientSaveResponse(
        String status,
        @JsonProperty("dish_uid") UUID dishUid,
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("ingredient_name") String ingredientName,
        @JsonProperty("custom_name") String customName,
        IngredientSource source,
        @JsonProperty("suggestion_uid") UUID suggestionUid,
        @JsonProperty("approval_status") String approvalStatus,
        @JsonProperty("created_by_id") Long createdById,
        @JsonProperty("updated_by_id") Long updatedById,
        NutritionValuesResponse nutritions,
        List<WarningResponse> warnings,
        double confidence
) {
}
