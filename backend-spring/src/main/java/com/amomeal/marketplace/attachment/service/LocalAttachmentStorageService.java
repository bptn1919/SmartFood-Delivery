package com.amomeal.marketplace.attachment.service;

import com.amomeal.marketplace.attachment.AttachmentStorageProperties;
import com.amomeal.marketplace.attachment.entity.Attachment;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Local filesystem {@link AttachmentStorageService} — an explicit opt-in
 * (see {@code app.storage.backend=local}) for running without AWS
 * credentials (dev/demo). {@link S3AttachmentStorageService} is the default;
 * see that class's javadoc / {@link AttachmentStorageService}'s javadoc for
 * the storage-backend decision history.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.storage", name = "backend", havingValue = "local")
public class LocalAttachmentStorageService implements AttachmentStorageService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AttachmentStorageProperties properties;

    /**
     * Also assigns a fresh {@code uploadToken} onto {@code attachment} as a side
     * effect (the local-storage substitute for an S3 presigned URL's signature
     * — see AttachmentStorageService PORT-NOTE). Caller is responsible for
     * persisting the entity afterward.
     */
    @Override
    public String generateUploadUrl(Attachment attachment) {
        String token = generateToken();
        attachment.setUploadToken(token);
        return "%s/%s/upload?token=%s".formatted(properties.uploadBaseUrl(), attachment.getUid(), token);
    }

    @Override
    public String buildPublicUrl(Attachment attachment) {
        return "%s/%s/%s".formatted(properties.publicBaseUrl(), attachment.getDirectory(), attachment.getHashedName());
    }

    @Override
    public boolean fileExists(Attachment attachment) {
        return Files.exists(resolvePath(attachment));
    }

    @Override
    public void storeFile(Attachment attachment, InputStream content, long contentLength) throws IOException {
        Path path = resolvePath(attachment);
        Files.createDirectories(path.getParent());
        try (content) {
            Files.copy(content, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public String bucketName() {
        return "local";
    }

    private Path resolvePath(Attachment attachment) {
        return Path.of(properties.localRoot(), attachment.getDirectory(), attachment.getHashedName());
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
