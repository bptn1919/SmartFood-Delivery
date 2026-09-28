package com.amomeal.marketplace.recommendation.dto;

import jakarta.validation.constraints.Pattern;

/** Mirrors schemas/requests.py::DailyMealLogUpdateRequest — every field optional. */
public record DailyMealLogUpdateRequest(
        String mealName,
        @Pattern(regexp = "BREAKFAST|LUNCH|DINNER|SNACK|UNKNOWN") String mealTime,
        Double quantityMultiplier,
        String dishUid
) {
}
