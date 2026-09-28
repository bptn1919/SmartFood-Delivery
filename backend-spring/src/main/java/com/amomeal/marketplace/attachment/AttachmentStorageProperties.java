package com.amomeal.marketplace.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.storage.*} from application.yml. Mirrors
 * ../../backend/marketplace/settings.py's USE_S3 / S3_* / MEDIA_ROOT / MEDIA_URL
 * block. {@code backend} selects which
 * {@link com.amomeal.marketplace.attachment.service.AttachmentStorageService}
 * bean is active (see {@code @ConditionalOnProperty} on
 * {@code S3AttachmentStorageService}/{@code LocalAttachmentStorageService}).
 *
 * <p><b>Resolved 2026-09-22 (was an open question):</b> the user chose real S3
 * as the default backend (matching Django's actual behavior — its
 * {@code AttachmentService} hard-requires {@code USE_S3}, there's no working
 * Django code path without it), with local filesystem storage kept as an
 * explicit opt-in for running without AWS credentials (dev/demo).
 *
 * @param backend             "s3" (default) or "local" — see PROGRESS.md.
 * @param localRoot           local-backend only: filesystem directory files are
 *                             written under (mirrors Django's {@code MEDIA_ROOT}).
 * @param publicBaseUrl       local-backend only: base URL the final,
 *                             publicly-viewable file is served from (mirrors
 *                             Django's {@code MEDIA_URL}); served by this app's
 *                             own {@code WebConfig} {@code /media/**} handler.
 * @param uploadBaseUrl       local-backend only: base URL of this server's own
 *                             attachment controller, used to build the
 *                             local-substitute "presigned" upload URL.
 * @param s3AccessKeyId       mirrors Django's {@code S3_ACCESS_KEY_ID}.
 * @param s3SecretAccessKey   mirrors Django's {@code S3_SECRET_ACCESS_KEY}.
 * @param s3BucketName        mirrors Django's {@code S3_BUCKET_NAME}.
 * @param s3Region            mirrors Django's {@code S3_REGION}.
 * @param s3PublicUrl         mirrors Django's {@code S3_PUBLIC_URL} (base URL
 *                             used to build {@code Attachment.publicUrl}).
 * @param s3ExpiresIn         seconds, mirrors Django's {@code S3_EXPIRES_IN}
 *                             (default 3600) — presigned PUT URL expiry.
 * @param s3EndpointOverride  NOT in Django — lets the S3 client point at a
 *                             non-AWS endpoint (LocalStack) for tests only. Left
 *                             blank in real deployments, where the SDK talks to
 *                             real AWS.
 */
@ConfigurationProperties(prefix = "app.storage")
public record AttachmentStorageProperties(
        String backend,
        String localRoot,
        String publicBaseUrl,
        String uploadBaseUrl,
        String s3AccessKeyId,
        String s3SecretAccessKey,
        String s3BucketName,
        String s3Region,
        String s3PublicUrl,
        int s3ExpiresIn,
        String s3EndpointOverride
) {
}
