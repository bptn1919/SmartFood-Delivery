package com.amomeal.marketplace.recommendation.dto;

/** Mirrors schemas/responses.py::DailyNutritionProfileResponse (profile fields + the summary). */
public record DailyNutritionProfileResponse(
        int age,
        String gender,
        double heightCm,
        double weightKg,
        String activityLevel,
        String goal,
        String date,
        double bmrKcal,
        double tdeeKcal,
        NutritionValuesResponse target,
        NutritionValuesResponse consumed,
        NutritionValuesResponse remaining
) {
}
