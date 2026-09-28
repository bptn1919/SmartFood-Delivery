package com.amomeal.marketplace.attachment.dto;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Mirrors ../../backend/attachment/schemas/responses.py::AttachmentResponse —
 * the nested shape other modules (dish, ingredient, certificate, review, ...)
 * embed when they include attachment info in their own responses. Not wired
 * into any controller yet (those modules aren't ported), provided here as the
 * shared DTO those future ports should reuse rather than re-declaring.
 */
public record AttachmentResponse(
        UUID uid,
        @JsonProperty("original_name") String originalName,
        @JsonProperty("public_url") String publicUrl
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(attachment.getUid(), attachment.getOriginalName(), attachment.getPublicUrl());
    }
}
