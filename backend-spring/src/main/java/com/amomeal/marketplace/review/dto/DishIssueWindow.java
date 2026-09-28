package com.amomeal.marketplace.review.dto;

import java.util.Map;

/** Mirrors ../../backend/review/schemas/responses.py::DishIssueWindowSchema. */
public record DishIssueWindow(boolean hasIssue, int totalIssues, Map<String, Integer> issues) {
}
