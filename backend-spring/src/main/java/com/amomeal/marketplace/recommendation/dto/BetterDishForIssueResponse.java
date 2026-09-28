package com.amomeal.marketplace.recommendation.dto;

import java.util.List;

/** Mirrors schemas/responses.py::BetterDishForIssueResponse. */
public record BetterDishForIssueResponse(String issue, String sourceDishUid, List<BetterDishItem> items) {
}
