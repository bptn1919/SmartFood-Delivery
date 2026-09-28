package com.amomeal.marketplace.dish.dto;

import java.util.Map;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::NutritionTotal — the
 * customer-facing 7-field nutrition total (the chef-facing variant uses the
 * full {@link NutritionValuesResponse} instead).
 */
public record NutritionTotalResponse(
        Double energy,
        Double protein,
        Double lipid,
        Double carbohydrate,
        Double fiber,
        Double natri,
        Double cholesterol
) {
    public static NutritionTotalResponse from(Map<String, Double> values) {
        return new NutritionTotalResponse(
                values.get("energy"),
                values.get("protein"),
                values.get("lipid"),
                values.get("carbohydrate"),
                values.get("fiber"),
                values.get("natri"),
                values.get("cholesterol"));
    }
}
