package com.amomeal.marketplace.recommendation.dto;

import java.util.Map;

/** Mirrors schemas/responses.py::RecommendationFeedMetaResponse. */
public record RecommendationFeedMetaResponse(
        int limit,
        int offset,
        int candidateCount,
        int scoredCount,
        int rerankedCount,
        double issueConfidence,
        int issueDataPoints,
        int latencyMs,
        Map<String, Double> weights,
        double mmrLambda,
        double penaltyGamma,
        Boolean annCandidatesEnabled,
        String userVectorSource
) {
}
