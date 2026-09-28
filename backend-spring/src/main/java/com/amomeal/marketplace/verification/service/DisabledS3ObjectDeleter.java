package com.amomeal.marketplace.verification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local-storage backend (Django USE_S3=False): nothing to delete, all deletion paths are skipped. */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "backend", havingValue = "local")
public class DisabledS3ObjectDeleter implements S3ObjectDeleter {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void delete(String bucket, String key) {
        throw new UnsupportedOperationException("S3 deletion is disabled (local storage backend)");
    }
}
