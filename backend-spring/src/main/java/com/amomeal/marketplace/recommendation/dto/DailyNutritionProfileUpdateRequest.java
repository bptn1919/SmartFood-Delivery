package com.amomeal.marketplace.recommendation.dto;

/** Mirrors schemas/requests.py::DailyNutritionProfileUpdateRequest — every field optional. */
public record DailyNutritionProfileUpdateRequest(
        Integer age,
        String gender,
        Double heightCm,
        Double weightKg,
        String activityLevel,
        String goal
) {
}
