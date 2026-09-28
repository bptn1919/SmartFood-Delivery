package com.amomeal.marketplace.certificate.service;

import java.util.UUID;

/**
 * Seam for Django's CertificateService._cleanup_selfie_if_fully_reviewed, which reaches into
 * verification (ChefVerificationSession, S3-deletion scheduling) after every admin status change.
 * verification is not ported yet; default is a no-op. The verification port should register a
 * {@code @Primary} bean. Failures are swallowed by the caller exactly like Django's blanket try/except.
 */
public interface CertificateReviewHook {
    void afterReviewed(UUID certificateUid);
}
