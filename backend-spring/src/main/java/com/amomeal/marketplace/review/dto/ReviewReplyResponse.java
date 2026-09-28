package com.amomeal.marketplace.review.dto;

import com.amomeal.marketplace.review.entity.ReviewReply;

import java.time.Instant;
import java.util.UUID;

/** Mirrors ../../backend/review/schemas/responses.py::ReviewReplyResponse. */
public record ReviewReplyResponse(
        UUID uid,
        String content,
        Instant createdAt,
        Instant updatedAt,
        ReviewOwnerInfo ownerInfo
) {
    public static ReviewReplyResponse of(ReviewReply reply) {
        if (reply == null) {
            return null;
        }
        return new ReviewReplyResponse(reply.getUid(), reply.getContent(), reply.getCreatedAt(),
                reply.getUpdatedAt(), ReviewOwnerInfo.ofReply(reply.getOwner()));
    }
}
