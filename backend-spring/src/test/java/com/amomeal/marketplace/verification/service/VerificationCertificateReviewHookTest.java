package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Port of CertificateService._cleanup_selfie_if_fully_reviewed. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificationCertificateReviewHookTest {

    @Mock private ChefVerificationSessionRepository sessionRepository;
    @Mock private CertificateRepository certificateRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private S3DeletionService s3DeletionService;

    private VerificationCertificateReviewHook hook;
    private ChefVerificationSession session;
    private final UUID biz = UUID.randomUUID();
    private final UUID fs = UUID.randomUUID();
    private Attachment cccd1;
    private Attachment cccd2;
    private Attachment selfie;

    @BeforeEach
    void setUp() {
        hook = new VerificationCertificateReviewHook(sessionRepository, certificateRepository, attachmentRepository,
                s3DeletionService);
        cccd1 = Attachment.builder().uid(UUID.randomUUID()).build();
        cccd2 = Attachment.builder().uid(UUID.randomUUID()).build();
        selfie = Attachment.builder().uid(UUID.randomUUID()).build();
        session = new ChefVerificationSession();
        session.setBusinessCertificateUid(biz);
        session.setFoodSafetyCertificateUid(fs);
        session.setCccdAttachmentUids(new ArrayList<>(List.of(cccd1.getUid().toString(), cccd2.getUid().toString())));
        session.setSelfieAttachmentUid(selfie.getUid());
        when(sessionRepository.findFirstByBusinessCertificateUid(biz)).thenReturn(Optional.of(session));
        when(sessionRepository.findFirstByFoodSafetyCertificateUid(fs)).thenReturn(Optional.of(session));
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(cccd1.getUid())).thenReturn(Optional.of(cccd1));
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(cccd2.getUid())).thenReturn(Optional.of(cccd2));
        when(attachmentRepository.findById(selfie.getUid())).thenReturn(Optional.of(selfie));
    }

    private void certStatus(UUID uid, CertificateStatus status) {
        when(certificateRepository.findById(uid)).thenReturn(Optional.of(Certificate.builder().uid(uid).status(status).build()));
    }

    @Test
    void noSessionForCertificate_isNoOp() {
        UUID other = UUID.randomUUID();
        when(sessionRepository.findFirstByBusinessCertificateUid(other)).thenReturn(Optional.empty());
        when(sessionRepository.findFirstByFoodSafetyCertificateUid(other)).thenReturn(Optional.empty());
        hook.afterReviewed(other);
        verifyNoInteractions(s3DeletionService);
    }

    @Test
    void anotherCertificateStillPending_schedulesNothing() {
        certStatus(biz, CertificateStatus.ACTIVE);
        certStatus(fs, CertificateStatus.PENDING);
        hook.afterReviewed(biz);
        verifyNoInteractions(s3DeletionService);
        assertThat(session.getCccdAttachmentUids()).hasSize(2);
        assertThat(session.getSelfieAttachmentUid()).isEqualTo(selfie.getUid());
    }

    @Test
    void allReviewed_schedulesCccdAndSelfieIn30Days_andClearsReferences() {
        certStatus(biz, CertificateStatus.ACTIVE);
        certStatus(fs, CertificateStatus.REVOKED);

        hook.afterReviewed(fs); // found through the food-safety FK lookup

        verify(s3DeletionService).schedule(cccd1, 30);
        verify(s3DeletionService).schedule(cccd2, 30);
        verify(s3DeletionService).schedule(selfie, 30);
        assertThat(session.getCccdAttachmentUids()).isEmpty();
        assertThat(session.getSelfieAttachmentUid()).isNull();
        verify(sessionRepository).save(session);
    }

    @Test
    void onlyOneCertificateExisted_andItIsReviewed_schedules() {
        session.setFoodSafetyCertificateUid(null);
        certStatus(biz, CertificateStatus.ACTIVE);
        hook.afterReviewed(biz);
        verify(s3DeletionService, times(3)).schedule(any(), org.mockito.ArgumentMatchers.eq(30));
    }

    @Test
    void perAttachmentSchedulingFailure_isSkipped_restStillProcessed() {
        certStatus(biz, CertificateStatus.ACTIVE);
        certStatus(fs, CertificateStatus.ACTIVE);
        doThrow(new IllegalStateException("db")).when(s3DeletionService).schedule(cccd1, 30);

        hook.afterReviewed(biz);

        verify(s3DeletionService).schedule(cccd2, 30);
        verify(s3DeletionService).schedule(selfie, 30);
        assertThat(session.getSelfieAttachmentUid()).isNull();
    }

    @Test
    void anyUnexpectedFailure_isSwallowed() {
        when(sessionRepository.findFirstByBusinessCertificateUid(biz)).thenThrow(new IllegalStateException("db down"));
        hook.afterReviewed(biz); // must not throw (Django blanket try/except)
    }
}
