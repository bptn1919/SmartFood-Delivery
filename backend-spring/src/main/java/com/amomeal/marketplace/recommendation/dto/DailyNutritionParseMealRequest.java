package com.amomeal.marketplace.recommendation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Mirrors schemas/requests.py::DailyNutritionParseMealRequest ({@code meal_time} is a Literal, default UNKNOWN). */
public record DailyNutritionParseMealRequest(
        @NotNull String text,
        @Pattern(regexp = "BREAKFAST|LUNCH|DINNER|SNACK|UNKNOWN") String mealTime
) {
    public DailyNutritionParseMealRequest {
        mealTime = mealTime == null ? "UNKNOWN" : mealTime;
    }
}
