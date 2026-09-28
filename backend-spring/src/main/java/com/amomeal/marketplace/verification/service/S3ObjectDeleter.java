package com.amomeal.marketplace.verification.service;

/**
 * Deletes a physical object from the attachment bucket (Django: boto3 {@code delete_object}).
 * {@link #isEnabled()} mirrors Django's {@code settings.USE_S3} gate: when false, every deletion /
 * scheduling path is skipped. Tests substitute a recording fake.
 */
public interface S3ObjectDeleter {

    boolean isEnabled();

    /** @throws RuntimeException if the delete fails (callers log/skip exactly like Django). */
    void delete(String bucket, String key);
}
