package com.amomeal.marketplace.recommendation.dto;

import java.util.Map;

/** Mirrors schemas/responses.py::DailyMealLogItemResponse (the service's *_uncertainty keys are dropped by the schema). */
public record DailyMealLogItemResponse(
        String uid,
        String source,
        String mealTime,
        String dishUid,
        String dishName,
        String mealName,
        double quantityMultiplier,
        double nutritionProteinG,
        double nutritionLipidG,
        double nutritionCarbG,
        double nutritionSodiumMg,
        double nutritionFiberG,
        double confidenceParse,
        double confidenceSource,
        Map<String, Object> rawPayload,
        String imageUrl,
        Double price,
        String createdAt,
        String updatedAt
) {
}
