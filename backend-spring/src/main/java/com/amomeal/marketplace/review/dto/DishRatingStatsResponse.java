package com.amomeal.marketplace.review.dto;

import java.util.Map;

/** Mirrors ../../backend/review/schemas/responses.py::DishRatingStatsSchema. */
public record DishRatingStatsResponse(double avgRating, long totalReviews, Map<Integer, Long> ratingDistribution) {
}
