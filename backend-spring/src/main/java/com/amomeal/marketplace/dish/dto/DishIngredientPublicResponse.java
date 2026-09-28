package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishIngredientPublicResponse
 * — what GET /api/dishes/{uid}/ingredients returns to a customer (no
 * confidence-per-ingredient, no source/approval internals).
 */
public record DishIngredientPublicResponse(
        @JsonProperty("confidence_of_dish") Double confidenceOfDish,
        @JsonProperty("confidence_text") String confidenceText,
        String note,
        List<PublicIngredientItem> ingredients,
        @JsonProperty("nutrition_total") NutritionTotalResponse nutritionTotal
) {

    /** Mirrors responses.py::IngredientOfDish. */
    public record PublicIngredientItem(
            @JsonProperty("ingredient_name") String ingredientName,
            Double weight,
            Double energy,
            Double protein,
            Double lipid,
            Double carbohydrate,
            Double fiber,
            Double natri,
            Double cholesterol
    ) {
    }
}
