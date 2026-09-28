package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.dto.CertificateRequest;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateAttachment;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.certificate.service.CertificateService;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.amomeal.marketplace.verification.gemini.GeminiVisionService.truthy;

/**
 * Post-decision steps of verification.py (_delete_sensitive_images, _finalize_verification,
 * _create_certificate_from_session). Each public method is its own transaction (or none, for the
 * S3 work) so that - as in Django's autocommit - a failure in one step never undoes the others;
 * {@link VerificationService} wraps each call in the same try/catch-and-log Django uses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationFinalizer {

    /** Argon2id, same cost params as Django's _cccd_hasher: t=2, m=64 MiB, p=1, hash 32, salt 16. */
    private static final Argon2PasswordEncoder CCCD_HASHER = new Argon2PasswordEncoder(16, 32, 1, 65536, 2);

    private final ChefVerificationSessionRepository sessionRepository;
    private final AttachmentRepository attachmentRepository;
    private final S3DeletionService s3DeletionService;
    private final CertificateService certificateService;
    private final CertificateRepository certificateRepository;
    private final CertificateAttachmentRepository certificateAttachmentRepository;
    private final CustomUserRepository customUserRepository;

    static String maskCccdNumber(String number) {
        if (number.length() <= 6) {
            return number;
        }
        return number.substring(0, 3) + "*".repeat(number.length() - 6) + number.substring(number.length() - 3);
    }

    /**
     * _delete_sensitive_images: VERIFIED/REJECTED delete CCCD + selfie from S3 right away;
     * PENDING_REVIEW keeps them for the admin (scheduled later by the certificate review hook).
     * Not transactional - external S3 calls; per-file failures are logged and skipped like Django.
     */
    public void deleteSensitiveImages(Long sessionId) {
        ChefVerificationSession session = sessionRepository.findById(sessionId).orElseThrow();
        if ("PENDING_REVIEW".equals(session.getDecision())) {
            return;
        }
        for (String uid : List.copyOf(session.getCccdAttachmentUids())) {
            try {
                attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(UUID.fromString(uid))
                        .ifPresent(s3DeletionService::deleteNow);
            } catch (RuntimeException e) {
                log.error("S3 delete failed for CCCD {}: {}", uid, e.getMessage());
            }
        }
        if (session.getSelfieAttachmentUid() != null) {
            try {
                attachmentRepository.findById(session.getSelfieAttachmentUid()).ifPresent(s3DeletionService::deleteNow);
            } catch (RuntimeException e) {
                log.error("S3 delete failed for selfie: {}", e.getMessage());
            }
        }
    }

    /** _finalize_verification steps 1-4: masked+hashed CCCD number, safe identity, wipe raw data. */
    @Transactional
    public void storeSafeIdentity(Long sessionId) {
        ChefVerificationSession session = sessionRepository.findById(sessionId).orElseThrow();
        Map<String, Object> cccd = session.getCccdConfirmed() == null ? Map.of() : session.getCccdConfirmed();

        Object rawObj = cccd.containsKey("cccd_number") ? cccd.get("cccd_number") : "";
        if (truthy(rawObj)) {
            String raw = rawObj.toString();
            session.setCccdNumberMasked(maskCccdNumber(raw));
            session.setCccdNumberHash(CCCD_HASHER.encode(raw));
        }

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("full_name", cccd.get("full_name"));
        identity.put("date_of_birth", cccd.get("date_of_birth"));
        session.setVerifiedIdentity(identity);
        session.setVerifiedAt(Instant.now());

        session.setCccdExtracted(null);
        session.setCccdConfirmed(null);

        if (!"PENDING_REVIEW".equals(session.getDecision())) {
            session.getCccdAttachmentUids().clear();
            session.setSelfieAttachmentUid(null);
        }
        sessionRepository.save(session);
    }

    /** Step 5 (business): create the PENDING BUSINESS_LICENSE certificate unless REJECTED / already created. */
    @Transactional
    public void createBusinessCertificate(Long sessionId) {
        ChefVerificationSession session = sessionRepository.findById(sessionId).orElseThrow();
        if ("REJECTED".equals(session.getDecision())) {
            return;
        }
        if (truthy(session.getBusinessConfirmed()) && !session.getBusinessAttachmentUids().isEmpty()
                && session.getBusinessCertificateUid() == null) {
            UUID certUid = createCertificate(session, CertificateType.BUSINESS_LICENSE,
                    session.getBusinessConfirmed(), session.getBusinessAttachmentUids());
            session.setBusinessCertificateUid(certUid);
            sessionRepository.save(session);
        }
    }

    /** Step 5 (food safety): create the PENDING FOOD_SAFETY certificate unless REJECTED / already created. */
    @Transactional
    public void createFoodSafetyCertificate(Long sessionId) {
        ChefVerificationSession session = sessionRepository.findById(sessionId).orElseThrow();
        if ("REJECTED".equals(session.getDecision())) {
            return;
        }
        if (truthy(session.getFoodSafetyConfirmed()) && !session.getFoodSafetyAttachmentUids().isEmpty()
                && session.getFoodSafetyCertificateUid() == null) {
            UUID certUid = createCertificate(session, CertificateType.FOOD_SAFETY,
                    session.getFoodSafetyConfirmed(), session.getFoodSafetyAttachmentUids());
            session.setFoodSafetyCertificateUid(certUid);
            sessionRepository.save(session);
        }
    }

    private static String firstTruthy(Map<String, Object> data, String fallback, String... keys) {
        for (String key : keys) {
            Object v = data.get(key);
            if (truthy(v)) {
                return v.toString();
            }
        }
        return fallback;
    }

    private static LocalDate parseDate(Object value) {
        if (!truthy(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * _create_certificate_from_session. The AI never auto-approves: the certificate is always
     * PENDING (admin review queue); one CertificateAttachment per page, positions max+1.
     */
    private UUID createCertificate(ChefVerificationSession session, CertificateType type,
                                   Map<String, Object> data, List<String> attachmentUids) {
        String name;
        String issuedBy;
        LocalDate expiry = null;
        if (type == CertificateType.BUSINESS_LICENSE) {
            name = firstTruthy(data, "Giấy ĐKKD", "business_name", "owner_name");
            issuedBy = "Cơ quan đăng ký kinh doanh";
        } else {
            name = firstTruthy(data, "Giấy ATTP", "facility_name", "owner_name");
            issuedBy = "Cơ quan an toàn thực phẩm";
            expiry = parseDate(data.get("expiry_date"));
        }
        LocalDate issue = parseDate(data.get("issue_date"));

        var owner = customUserRepository.getReferenceById(session.getUserId());
        UUID certUid = certificateService.createCertificate(owner, new CertificateRequest(
                name, null, issuedBy, issue != null ? issue : LocalDate.now(), expiry, type)).uid();

        Certificate cert = certificateRepository.getReferenceById(certUid);
        for (String uidStr : attachmentUids) {
            Attachment att = attachmentRepository
                    .findByUidAndIsDeletedFalseAndIsFileDeletedFalse(UUID.fromString(uidStr)).orElse(null);
            if (att != null) {
                Integer max = certificateAttachmentRepository.findMaxPosition(cert);
                certificateAttachmentRepository.saveAndFlush(CertificateAttachment.builder()
                        .certificate(cert).attachment(att).position((max == null ? 0 : max) + 1).build());
            }
        }
        return certUid;
    }
}
