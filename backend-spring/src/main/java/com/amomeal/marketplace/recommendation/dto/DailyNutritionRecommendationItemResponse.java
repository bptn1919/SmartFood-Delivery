package com.amomeal.marketplace.recommendation.dto;

import java.util.List;

/** Mirrors schemas/responses.py::DailyNutritionRecommendationItemResponse (the service's rank_position key is dropped by the schema). */
public record DailyNutritionRecommendationItemResponse(
        String dishUid,
        String dishName,
        String publicUrl,
        double price,
        double avgRating,
        double baseRecommendationScore,
        double macroMatchScore,
        double finalScore,
        double suggestedServings,
        NutritionValuesResponse nutritionImpact,
        List<String> reasons
) {
}
