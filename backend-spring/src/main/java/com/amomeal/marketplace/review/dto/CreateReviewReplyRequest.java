package com.amomeal.marketplace.review.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Mirrors ../../backend/review/schemas/requests.py::CreateReviewReplySchema.
 * {@code content: str} is required (but not non-empty) in Django's schema — {@code @NotNull},
 * not {@code @NotBlank}, to allow an empty string exactly like Django would.
 */
public record CreateReviewReplyRequest(@NotNull String content) {
}
