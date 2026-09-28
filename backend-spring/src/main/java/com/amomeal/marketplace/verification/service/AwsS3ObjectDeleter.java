package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.AttachmentStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.net.URI;

/** Real S3 deletion (active when the attachment storage backend is s3, i.e. Django USE_S3=True). */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "backend", havingValue = "s3", matchIfMissing = true)
public class AwsS3ObjectDeleter implements S3ObjectDeleter {

    private final S3Client s3;

    public AwsS3ObjectDeleter(AttachmentStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.s3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.s3AccessKeyId(), properties.s3SecretAccessKey())));
        if (StringUtils.hasText(properties.s3EndpointOverride())) {
            builder.endpointOverride(URI.create(properties.s3EndpointOverride())).forcePathStyle(true);
        }
        this.s3 = builder.build();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void delete(String bucket, String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
