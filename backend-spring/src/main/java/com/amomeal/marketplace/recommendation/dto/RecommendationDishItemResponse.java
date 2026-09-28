package com.amomeal.marketplace.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors schemas/responses.py::RecommendationDishItemResponse. */
public record RecommendationDishItemResponse(
        String dishUid,
        String dishName,
        String publicUrl,
        double price,
        double avgRating,
        @JsonProperty("is_favorite") boolean isFavorite,
        boolean allergyWarning,
        double score,
        double baseScore,
        double favoriteScore,
        double historyScore,
        double issuePenalty,
        /* Backward-compatible (deprecated) name of the mismatch penalty. */
        Double nutritionPenalty,
        Double preferenceNutritionMismatchPenalty,
        List<String> reasons
) {
}
