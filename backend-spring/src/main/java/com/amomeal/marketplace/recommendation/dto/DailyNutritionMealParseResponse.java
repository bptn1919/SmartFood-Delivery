package com.amomeal.marketplace.recommendation.dto;

import java.util.List;

/** Mirrors schemas/responses.py::DailyNutritionMealParseResponse. */
public record DailyNutritionMealParseResponse(DailyNutritionSummaryResponse summary, int parsedCount,
                                              List<String> unresolvedMeals) {
}
