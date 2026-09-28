package com.amomeal.marketplace.review.dto;

import java.util.UUID;

/** Mirrors ../../backend/review/schemas/requests.py::UpdateReviewSchema — all fields optional. */
public record UpdateReviewRequest(
        Integer rating,
        String comment,
        UUID attachmentUid
) {
}
