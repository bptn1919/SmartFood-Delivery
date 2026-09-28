package com.amomeal.marketplace.review.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Mirrors ../../backend/review/schemas/requests.py::CreateReviewSchema.
 * {@code dish_uid}/{@code order_uid}/{@code rating} are non-Optional in Django's ninja
 * Schema (missing/wrong-typed -> the project-wide 401 VALIDATION_ERROR quirk, CLAUDE.md §6) —
 * {@code @NotNull} + the controller's {@code @Valid} reproduce that; the 1-5 RANGE check
 * (a plain {@code int} in Django's schema, not a schema-level constraint) stays in
 * {@code ReviewService} as {@code InvalidRatingException}, matching Django exactly.
 */
public record CreateReviewRequest(
        @NotNull UUID dishUid,
        @NotNull UUID orderUid,
        @NotNull Integer rating,
        String comment,
        UUID attachmentUid
) {
}
