package com.amomeal.marketplace.recommendation.dto;

import java.util.Map;

/**
 * Mirrors schemas/responses.py::DailyNutritionTargetResponse — exactly the five macro keys. The
 * service dicts carry extra *_uncertainty / *_lower / *_upper keys, but ninja validates the
 * response through this schema and drops them, so they never reach the client.
 */
public record NutritionValuesResponse(double proteinG, double lipidG, double carbG, double sodiumMg, double fiberG) {

    public static NutritionValuesResponse of(Map<String, Double> values) {
        return new NutritionValuesResponse(values.get("protein_g"), values.get("lipid_g"), values.get("carb_g"),
                values.get("sodium_mg"), values.get("fiber_g"));
    }
}
