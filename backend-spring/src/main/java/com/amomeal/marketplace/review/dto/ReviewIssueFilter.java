package com.amomeal.marketplace.review.dto;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/review/schemas/requests.py::FilterIssueReviewSchema
 * (query params on the chef analytics endpoints). All fields optional.
 */
public record ReviewIssueFilter(UUID dishUid, UUID menuUid, String search, List<String> categories) {

    public static final ReviewIssueFilter EMPTY = new ReviewIssueFilter(null, null, null, null);
}
