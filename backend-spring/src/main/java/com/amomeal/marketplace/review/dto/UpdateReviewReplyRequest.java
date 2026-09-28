package com.amomeal.marketplace.review.dto;

import jakarta.validation.constraints.NotNull;

/** Mirrors ../../backend/review/schemas/requests.py::UpdateReviewReplySchema — {@code content: str} required. */
public record UpdateReviewReplyRequest(@NotNull String content) {
}
