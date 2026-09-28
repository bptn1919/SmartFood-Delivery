package com.amomeal.marketplace.review.dto;

import java.time.Instant;
import java.util.List;

/** Mirrors ../../backend/review/schemas/responses.py::ChefIssueReportResponse. */
public record ChefIssueReportResponse(Instant rangeStart, Instant rangeEnd, Instant weekSplitAt,
                                       List<ChefDishIssueReport> reports) {
}
