package com.amomeal.marketplace.review.dto;

import java.time.LocalDate;
import java.util.List;

/** Mirrors ../../backend/review/schemas/responses.py::IssueReviewsResponse. */
public record IssueReviewsResponse(LocalDate rangeStart, LocalDate rangeEnd, String issue, int totalReviews,
                                    List<ReviewOfDishResponse> reviews) {
}
