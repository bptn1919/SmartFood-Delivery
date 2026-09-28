package com.amomeal.marketplace.attachment.dto;

import java.util.UUID;

/**
 * Mirrors ../../backend/attachment/schemas/responses.py::GeneratePresignedUrlResponse.
 * {@code url} is the URL the client PUTs the raw file bytes to — under S3 this
 * would be a presigned S3 PUT URL; under this port's default local-storage
 * backend it's this server's own {@code PUT /api/attachments/{uid}/upload}
 * endpoint with a capability token query param (see AttachmentStorageService
 * PORT-NOTE). Either way, the client-side contract (PUT the file body to
 * `url`, then call the completed endpoint) is unchanged.
 */
public record GeneratePresignedUrlResponse(UUID uid, String url) {
}
