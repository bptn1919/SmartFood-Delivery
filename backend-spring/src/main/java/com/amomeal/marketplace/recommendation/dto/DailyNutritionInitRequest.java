package com.amomeal.marketplace.recommendation.dto;

import jakarta.validation.constraints.NotNull;

/** Mirrors schemas/requests.py::DailyNutritionInitRequest (age/height_cm/weight_kg required, the rest defaulted). */
public record DailyNutritionInitRequest(
        @NotNull Integer age,
        String gender,
        @NotNull Double heightCm,
        @NotNull Double weightKg,
        String activityLevel,
        String goal
) {
    public DailyNutritionInitRequest {
        gender = gender == null ? "OTHER" : gender;
        activityLevel = activityLevel == null ? "LIGHT" : activityLevel;
        goal = goal == null ? "MAINTAIN" : goal;
    }
}
