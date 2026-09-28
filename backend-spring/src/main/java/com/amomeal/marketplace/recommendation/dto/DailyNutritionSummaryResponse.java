package com.amomeal.marketplace.recommendation.dto;

/** Mirrors schemas/responses.py::DailyNutritionSummaryResponse. */
public record DailyNutritionSummaryResponse(
        String date,
        double bmrKcal,
        double tdeeKcal,
        NutritionValuesResponse target,
        NutritionValuesResponse consumed,
        NutritionValuesResponse remaining
) {
}
