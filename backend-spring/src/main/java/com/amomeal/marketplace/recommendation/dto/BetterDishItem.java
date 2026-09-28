package com.amomeal.marketplace.recommendation.dto;

/** Mirrors schemas/responses.py::BetterDishItem. */
public record BetterDishItem(
        String dishUid,
        String dishName,
        String publicUrl,
        double price,
        double avgRating,
        int totalReviews,
        double issueRate,
        double score,
        String explain
) {
}
