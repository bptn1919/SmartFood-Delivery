package com.amomeal.marketplace.attachment.dto;

import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;

/**
 * Mirrors ../../backend/attachment/schemas/requests.py::GeneratePresignedUrlSchema.
 * Field names are snake_case on the wire, matching Django ninja's default JSON
 * shape and FE-admin/src/services/attachmentService.js's request body
 * ({@code file_name}/{@code file_size}/{@code attachment_type}) exactly —
 * deliberately NOT the camelCase convention used by the `users` module (that
 * module is flagged 🚩 in PROGRESS.md as not yet verified against FE-admin).
 */
public record GeneratePresignedUrlRequest(
        @JsonProperty("file_name") @NotBlank String fileName,
        @JsonProperty("file_size") @NotNull @Positive Integer fileSize,
        @JsonProperty("attachment_type") @NotNull AttachmentType attachmentType
) {
}
