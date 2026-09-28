package com.amomeal.marketplace.recommendation.dto;

import java.util.List;

/** Mirrors schemas/responses.py::DailyNutritionRecommendationResponse. */
public record DailyNutritionRecommendationResponse(DailyNutritionSummaryResponse summary,
                                                   List<DailyNutritionRecommendationItemResponse> items) {
}
