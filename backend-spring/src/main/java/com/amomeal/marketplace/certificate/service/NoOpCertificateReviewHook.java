package com.amomeal.marketplace.certificate.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default no-op CertificateReviewHook until verification is ported. */
@Component
public class NoOpCertificateReviewHook implements CertificateReviewHook {
    @Override
    public void afterReviewed(UUID certificateUid) {
        // nothing to clean up - no verification sessions exist yet
    }
}
