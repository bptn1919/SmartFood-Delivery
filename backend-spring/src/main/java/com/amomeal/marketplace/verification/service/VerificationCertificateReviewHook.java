package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.certificate.service.CertificateReviewHook;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Closes the {@link CertificateReviewHook} seam left by the certificate module: port of Django's
 * {@code CertificateService._cleanup_selfie_if_fully_reviewed}. After an admin reviews a
 * certificate, if the owning verification session has no PENDING certificate left, the CCCD
 * images + selfie are scheduled for S3 deletion 30 days out and the session's references to them
 * are cleared right away. Runs inside the caller's transaction (it must see the certificate status
 * the admin just saved); everything is swallowed and logged like Django's blanket try/except.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class VerificationCertificateReviewHook implements CertificateReviewHook {

    private static final int DELETE_DELAY_DAYS = 30;

    private final ChefVerificationSessionRepository sessionRepository;
    private final CertificateRepository certificateRepository;
    private final AttachmentRepository attachmentRepository;
    private final S3DeletionService s3DeletionService;

    @Override
    public void afterReviewed(UUID certificateUid) {
        try {
            Optional<ChefVerificationSession> found = sessionRepository.findFirstByBusinessCertificateUid(certificateUid);
            if (found.isEmpty()) {
                found = sessionRepository.findFirstByFoodSafetyCertificateUid(certificateUid);
            }
            if (found.isEmpty()) {
                return;
            }
            ChefVerificationSession session = found.get();

            boolean pendingExists = isPending(session.getBusinessCertificateUid())
                    || isPending(session.getFoodSafetyCertificateUid());
            if (pendingExists) {
                return; // not fully reviewed yet
            }

            for (String uidStr : session.getCccdAttachmentUids()) {
                try {
                    attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(UUID.fromString(uidStr))
                            .ifPresent(a -> s3DeletionService.schedule(a, DELETE_DELAY_DAYS));
                } catch (RuntimeException e) {
                    log.error("Schedule CCCD deletion failed for {}: {}", uidStr, e.getMessage());
                }
            }
            if (session.getSelfieAttachmentUid() != null) {
                try {
                    Optional<Attachment> selfie = attachmentRepository.findById(session.getSelfieAttachmentUid());
                    selfie.ifPresent(a -> s3DeletionService.schedule(a, DELETE_DELAY_DAYS));
                } catch (RuntimeException e) {
                    log.error("Schedule selfie deletion failed: {}", e.getMessage());
                }
            }

            session.getCccdAttachmentUids().clear();
            session.setSelfieAttachmentUid(null);
            sessionRepository.save(session);
        } catch (RuntimeException e) {
            log.error("_cleanup_selfie_if_fully_reviewed failed: {}", e.getMessage());
        }
    }

    private boolean isPending(UUID certificateUid) {
        if (certificateUid == null) {
            return false;
        }
        return certificateRepository.findById(certificateUid)
                .map(Certificate::getStatus)
                .filter(s -> s == CertificateStatus.PENDING)
                .isPresent();
    }
}
