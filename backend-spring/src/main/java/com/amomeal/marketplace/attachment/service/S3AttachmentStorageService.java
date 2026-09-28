package com.amomeal.marketplace.attachment.service;

import com.amomeal.marketplace.attachment.AttachmentStorageProperties;
import com.amomeal.marketplace.attachment.entity.Attachment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Default {@link AttachmentStorageService} — real S3, mirroring Django's
 * actual (and only working) behavior in
 * ../../backend/attachment/services.py::AttachmentService: a boto3 client
 * built from {@code S3_ACCESS_KEY_ID}/{@code S3_SECRET_ACCESS_KEY}/
 * {@code S3_REGION}, {@code generate_presigned_url(ClientMethod="put_object",
 * ExpiresIn=S3_EXPIRES_IN, HttpMethod="PUT")} for uploads,
 * {@code head_object} to verify a file landed before completing, and
 * {@code put_object} for the direct-upload path ({@code post_file}).
 *
 * <p>Resolved 2026-09-22 with the user (see PROGRESS.md): this is now the
 * default backend ({@code app.storage.backend=s3}), matching Django's actual
 * hard S3 requirement. {@link LocalAttachmentStorageService} remains as an
 * explicit {@code app.storage.backend=local} opt-in.
 *
 * <p>{@code s3EndpointOverride}/path-style addressing has NO Django
 * equivalent — added purely so this same class can point at a LocalStack
 * container in tests ({@code AttachmentS3ControllerTest}) without needing
 * real AWS credentials; left blank (real AWS) in every real deployment.
 */
@Service
@ConditionalOnProperty(prefix = "app.storage", name = "backend", havingValue = "s3", matchIfMissing = true)
public class S3AttachmentStorageService implements AttachmentStorageService {

    private final AttachmentStorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    public S3AttachmentStorageService(AttachmentStorageProperties properties) {
        this.properties = properties;

        AwsCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.s3AccessKeyId(), properties.s3SecretAccessKey()));
        Region region = Region.of(properties.s3Region());

        S3ClientBuilder clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials);

        if (StringUtils.hasText(properties.s3EndpointOverride())) {
            // PORT-NOTE: LocalStack-only escape hatch, see class javadoc — real deployments
            // leave this blank and talk to real AWS S3.
            URI endpoint = URI.create(properties.s3EndpointOverride());
            clientBuilder.endpointOverride(endpoint).forcePathStyle(true);
            presignerBuilder.endpointOverride(endpoint)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }

        this.s3Client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public String generateUploadUrl(Attachment attachment) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.s3BucketName())
                .key(key(attachment))
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.s3ExpiresIn()))
                .putObjectRequest(objectRequest)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    @Override
    public String buildPublicUrl(Attachment attachment) {
        String base = StringUtils.hasText(properties.s3PublicUrl())
                ? properties.s3PublicUrl()
                // Mirrors boto3/django-storages' default virtual-hosted-style URL shape
                // when S3_PUBLIC_URL isn't set — not an exact Django line-for-line port
                // (Django's AttachmentService always required S3_PUBLIC_URL to be set),
                // just a safe fallback so this never produces a blank public_url.
                : "https://%s.s3.%s.amazonaws.com".formatted(properties.s3BucketName(), properties.s3Region());
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return "%s/%s/%s".formatted(base, attachment.getDirectory(), attachment.getHashedName());
    }

    @Override
    public boolean fileExists(Attachment attachment) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.s3BucketName())
                    .key(key(attachment))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // S3's HeadObject returns a bare 404 (no XML body), which the SDK usually
            // surfaces as a generic S3Exception rather than NoSuchKeyException.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void storeFile(Attachment attachment, InputStream content, long contentLength) throws IOException {
        try (content) {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.s3BucketName())
                            .key(key(attachment))
                            .contentType(attachment.getContentType())
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
        }
    }

    @Override
    public String bucketName() {
        return properties.s3BucketName();
    }

    private String key(Attachment attachment) {
        return "%s/%s".formatted(attachment.getDirectory(), attachment.getHashedName());
    }
}
