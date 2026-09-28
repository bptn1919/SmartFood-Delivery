package com.amomeal.marketplace.review.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the {@code request_payload} dict built by
 * ../../backend/review/services/__init__.py::ReviewService._predict_review_label
 * before {@code POST {AI_MODEL_BASE_URL}/predict}.
 */
public record AiPredictionRequest(
        UUID reviewUid,
        Instant createdAt,
        Instant updatedAt,
        int rating,
        String comment,
        boolean deleted,
        UUID attachmentUid,
        UUID dishUid,
        UUID orderUid,
        Long ownerId
) {
}
