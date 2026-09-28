package com.amomeal.marketplace.attachment.service;

import com.amomeal.marketplace.attachment.entity.Attachment;

import java.io.IOException;
import java.io.InputStream;

/**
 * Storage backend abstraction for attachment file bytes. Exactly one
 * implementation is active at a time, selected by
 * {@code app.storage.backend} (see {@code @ConditionalOnProperty} on
 * {@link S3AttachmentStorageService} / {@link LocalAttachmentStorageService}).
 *
 * <p><b>Resolved (was flagged as a PORT-NOTE, now resolved with the user
 * 2026-09-22 — see PROGRESS.md):</b> Django's real {@code AttachmentService}
 * (../../backend/attachment/services.py) always talks to S3 via boto3 and in
 * fact *requires* it (raises {@code RuntimeError} at construction time if
 * {@code USE_S3} is false) — there is no working local-storage code path in
 * Django today, even though {@code settings.py} has a dormant, unused non-S3
 * branch (MEDIA_ROOT/MEDIA_URL). The user reviewed this and chose: implement
 * real S3 ({@link S3AttachmentStorageService}, AWS SDK v2) as the default,
 * matching Django's actual hard-required behavior, and keep
 * {@link LocalAttachmentStorageService} as an explicit opt-in
 * ({@code app.storage.backend=local}) for running without AWS credentials
 * (dev/demo) — it costs nothing to keep behind this same interface.
 */
public interface AttachmentStorageService {

    /**
     * Builds the URL the client PUTs the raw file bytes to. Under S3
     * ({@link S3AttachmentStorageService}) this is a real signed, time-limited
     * S3 PUT URL (mirrors {@code boto3 s3_client.generate_presigned_url});
     * under local storage it's this server's own upload endpoint plus a
     * capability token (see {@link Attachment#getUploadToken()} — no Django
     * equivalent, see {@link LocalAttachmentStorageService}).
     */
    String generateUploadUrl(Attachment attachment);

    /** Builds the final publicly-viewable URL, stored on {@code Attachment.publicUrl}. */
    String buildPublicUrl(Attachment attachment);

    /** Value recorded on {@code Attachment.bucket} — mirrors Django setting it to {@code S3_BUCKET_NAME}. */
    String bucketName();

    /**
     * Mirrors the {@code s3_client.head_object} existence check in
     * {@code AttachmentService.completed_upload} — verifies the file was actually
     * uploaded before allowing the completed-upload transition.
     */
    boolean fileExists(Attachment attachment);

    /**
     * Writes bytes to the backing store. Under S3 this mirrors
     * {@code s3_client.put_object} (used by Django's {@code post_file} and, in
     * this port, also by the local-substitute upload endpoint
     * {@code PUT /api/attachments/{uid}/upload}). {@code contentLength} is
     * required up front because S3's {@code PutObject} needs a known
     * Content-Length (AWS SDK v2's {@code RequestBody.fromInputStream}).
     */
    void storeFile(Attachment attachment, InputStream content, long contentLength) throws IOException;
}
