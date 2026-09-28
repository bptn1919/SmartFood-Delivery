package com.amomeal.marketplace.recommendation.dto;

import java.util.Map;

/**
 * Mirrors schemas/responses.py::UserIssueSensitivityProfileResponse. {@code issue_profile} keys are
 * the 26 Vietnamese labels in Django's DEFAULT_ISSUES order (map keys are not touched by the
 * snake_case naming strategy).
 */
public record UserIssueSensitivityProfileResponse(long userId, Map<String, Double> issueProfile, double confidence,
                                                  int dataPoints) {
}
