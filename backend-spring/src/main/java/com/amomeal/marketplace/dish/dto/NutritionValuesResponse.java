package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.service.NutrientFields;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishIngredientPreviewNutrition
 * (also used for {@code NutritionTotalDetail}) — all 19 nutrient fields,
 * nullable.
 */
public record NutritionValuesResponse(
        Double energy,
        Double protein,
        Double lipid,
        Double carbohydrate,
        Double fiber,
        Double natri,
        Double kali,
        Double cholesterol,
        Double retinol,
        Double caroten,
        @JsonProperty("vitamin_b_1") Double vitaminB1,
        @JsonProperty("vitamin_b_2") Double vitaminB2,
        @JsonProperty("vitamin_pp") Double vitaminPp,
        @JsonProperty("vitamin_c") Double vitaminC,
        Double calcium,
        Double phosphorus,
        Double fe,
        Double mg,
        Double zn
) {

    /** Builds the response from the field-keyed map the pipeline produces. */
    public static NutritionValuesResponse from(Map<String, Double> values) {
        return new NutritionValuesResponse(
                values.get("energy"),
                values.get("protein"),
                values.get("lipid"),
                values.get("carbohydrate"),
                values.get("fiber"),
                values.get("natri"),
                values.get("kali"),
                values.get("cholesterol"),
                values.get("retinol"),
                values.get("caroten"),
                values.get("vitamin_b_1"),
                values.get("vitamin_b_2"),
                values.get("vitamin_pp"),
                values.get("vitamin_c"),
                values.get("calcium"),
                values.get("phosphorus"),
                values.get("fe"),
                values.get("mg"),
                values.get("zn"));
    }

    /** Guards against a typo silently dropping a field from the response. */
    static {
        assert NutrientFields.DISH_NUTRIENT_FIELDS.size() == 19;
    }
}
