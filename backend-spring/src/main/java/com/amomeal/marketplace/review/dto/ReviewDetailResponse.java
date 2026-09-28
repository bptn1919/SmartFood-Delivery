package com.amomeal.marketplace.review.dto;

import com.amomeal.marketplace.review.entity.Review;

import java.time.Instant;
import java.util.UUID;

/** Mirrors ../../backend/review/schemas/responses.py::ReviewDetailResponse (no dish_info — used where the dish is already known from context). */
public record ReviewDetailResponse(
        UUID uid,
        int rating,
        String comment,
        String issue,
        Instant createdAt,
        Instant updatedAt,
        ReviewOwnerInfo ownerInfo,
        String attachmentUrl,
        ReviewReplyResponse reply
) {
    public static ReviewDetailResponse of(Review review) {
        return new ReviewDetailResponse(
                review.getUid(),
                review.getRating(),
                review.getComment(),
                review.getIssue(),
                review.getCreatedAt(),
                review.getUpdatedAt(),
                ReviewOwnerInfo.of(review.getOwner()),
                review.getAttachment() == null ? null : review.getAttachment().getPublicUrl(),
                review.getReply() != null && !review.getReply().isDeleted()
                        ? ReviewReplyResponse.of(review.getReply()) : null
        );
    }
}
