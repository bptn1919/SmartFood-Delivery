package com.amomeal.marketplace.recommendation.dto;

import java.util.List;

/** Mirrors schemas/responses.py::DailyMealLogListResponse. */
public record DailyMealLogListResponse(DailyNutritionSummaryResponse summary, List<DailyMealLogItemResponse> items) {
}
