package com.amomeal.marketplace.review.dto;

import com.amomeal.marketplace.review.entity.Review;

import java.time.Instant;
import java.util.UUID;

/** Mirrors ../../backend/review/schemas/responses.py::ReviewResponse. */
public record ReviewResponse(
        UUID uid,
        int rating,
        String comment,
        String issue,
        Instant createdAt,
        Instant updatedAt,
        ReviewOwnerInfo ownerInfo,
        ReviewDishInfo dishInfo,
        String attachmentUrl,
        ReviewReplyResponse reply
) {
    public static ReviewResponse of(Review review) {
        return new ReviewResponse(
                review.getUid(),
                review.getRating(),
                review.getComment(),
                review.getIssue(),
                review.getCreatedAt(),
                review.getUpdatedAt(),
                ReviewOwnerInfo.of(review.getOwner()),
                ReviewDishInfo.of(review.getDish()),
                review.getAttachment() == null ? null : review.getAttachment().getPublicUrl(),
                // Django: obj.reply if it exists AND is not soft-deleted.
                review.getReply() != null && !review.getReply().isDeleted()
                        ? ReviewReplyResponse.of(review.getReply()) : null
        );
    }
}
