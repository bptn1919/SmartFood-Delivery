package com.amomeal.marketplace.recommendation.dto;

import java.util.List;

/** Mirrors schemas/responses.py::RecommendationFeedResponse. */
public record RecommendationFeedResponse(List<RecommendationDishItemResponse> items, RecommendationFeedMetaResponse meta) {
}
