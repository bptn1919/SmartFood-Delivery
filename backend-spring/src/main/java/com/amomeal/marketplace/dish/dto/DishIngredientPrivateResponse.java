package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishIngredientPrivateResponse
 * — what GET /api/dishes/{uid}/ingredients/chef returns (adds per-ingredient
 * confidence/source/approval_status and the full micronutrient total), built by
 * Django's {@code build_response_chef}.
 *
 * <p>Django also puts {@code confidence_percent} on this payload (the ninja
 * schema silently drops it, but {@code build_response_chef} computes it and the
 * empty-dish early return includes it) — it is exposed here, since the FE reads
 * whatever the endpoint sends.
 */
public record DishIngredientPrivateResponse(
        @JsonProperty("confidence_of_dish") Double confidenceOfDish,
        @JsonProperty("confidence_percent") Double confidencePercent,
        @JsonProperty("confidence_text") String confidenceText,
        String note,
        List<PrivateIngredientItem> ingredients,
        @JsonProperty("nutrition_total") NutritionValuesResponse nutritionTotal
) {

    /** Mirrors the per-ingredient dict built by {@code build_response_chef}. */
    public record PrivateIngredientItem(
            UUID uid,
            @JsonProperty("ingredient_name") String ingredientName,
            @JsonProperty("is_custom") boolean custom,
            Double weight,
            Double energy,
            Double protein,
            Double lipid,
            Double carbohydrate,
            Double fiber,
            Double natri,
            Double cholesterol,
            Double confidence,
            IngredientSource source,
            @JsonProperty("approval_status") IngredientImportStatus approvalStatus,
            @JsonProperty("ingredient_uid") UUID ingredientUid
    ) {
    }
}
