package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.verification.dto.VerificationResponses.*;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.entity.DocumentStepStatus;
import com.amomeal.marketplace.verification.entity.VerificationSessionStatus;
import com.amomeal.marketplace.verification.exception.*;
import com.amomeal.marketplace.verification.gemini.GeminiVisionService;
import com.amomeal.marketplace.verification.gemini.GeminiVisionService.Analysis;
import com.amomeal.marketplace.verification.gemini.ImageFetcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.amomeal.marketplace.verification.gemini.GeminiVisionService.truthy;

/**
 * Port of ../../backend/verification/services/verification.py::VerificationService - the chef KYC
 * orchestrator: (1) analyze with Gemini OCR -> (2) chef confirms -> (3) cross-validate -> selfie code
 * -> selfie analyze -> face match -> risk score -> decision -> finalize (safe identity, certificates).
 *
 * <p>Intentionally NOT {@code @Transactional}: Django runs under autocommit and several paths
 * "persist, then raise" (failed cross-validation saves its errors, a session created by
 * get_or_create survives a later AttachmentNotFound...). Each repository call here is its own
 * transaction, so those writes stick exactly like Django's; see {@link VerificationSessionStore}
 * and CLAUDE.md section 8b.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx").withZone(ZoneOffset.UTC);

    private final VerificationSessionStore store;
    private final AttachmentService attachmentService;
    private final AttachmentRepository attachmentRepository;
    private final GeminiVisionService gemini;
    private final ImageFetcher imageFetcher;
    private final CccdQrScanner qrScanner;
    private final FaceMatcher faceMatcher;
    private final VerificationFinalizer finalizer;

    public static String iso(Instant instant) {
        return ISO_MICROS.format(instant);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Django _get_completed_attachment(s): AttachmentNotFound / AttachmentIsNotCompleted. */
    private List<Attachment> completedAttachments(List<UUID> uids) {
        List<Attachment> out = new ArrayList<>();
        for (UUID uid : uids) {
            out.add(attachmentService.handleAttachment(uid));
        }
        return out;
    }

    private static List<String> urls(List<Attachment> attachments) {
        return attachments.stream().map(Attachment::getPublicUrl).toList();
    }

    private static List<String> uidStrings(List<Attachment> attachments) {
        return attachments.stream().map(a -> a.getUid().toString()).toList();
    }

    private static List<Map<String, Object>> errorsOf(Analysis analysis) {
        return analysis.errors();
    }

    // ── CCCD ─────────────────────────────────────────────────────────────────

    public AnalyzeCccdResponse analyzeCccd(Long userId, List<UUID> attachmentUids) {
        ChefVerificationSession session = store.getOrCreate(userId);
        List<Attachment> attachments = completedAttachments(attachmentUids);

        Analysis result = gemini.analyzeCccd(urls(attachments));
        if (!result.errors().isEmpty()) {
            throw new VerificationDocumentException(errorsOf(result));
        }
        Map<String, Object> extracted = new LinkedHashMap<>(result.extracted());

        // Scan the QR on the back (index 1) and cross-check it against the OCR number.
        if (attachments.size() >= 2) {
            try {
                byte[] backBytes = imageFetcher.fetch(attachments.get(1).getPublicUrl()).data();
                Optional<Map<String, Object>> qr = qrScanner.scan(backBytes);
                if (qr.isPresent() && !qr.get().isEmpty()) {
                    Map<String, Object> qrData = qr.get();
                    Object ocrNumber = extracted.get("cccd_number");
                    Object qrNumber = qrData.get("cccd_number");
                    if (truthy(ocrNumber) && truthy(qrNumber) && !ocrNumber.toString().equals(qrNumber.toString())) {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("code", "CCCD_QR_NUMBER_MISMATCH");
                        err.put("message", "Số CCCD trong mã QR không khớp với số in trên thẻ. "
                                + "CCCD có thể bị giả mạo. Vui lòng kiểm tra lại.");
                        throw new VerificationDocumentException(List.of(err));
                    }
                    extracted.put("qr_verified", truthy(qrNumber));
                    extracted.put("qr_gender", qrData.get("gender"));
                    extracted.put("old_cmnd", qrData.get("old_cmnd"));
                } else {
                    extracted.put("qr_verified", false);
                }
            } catch (VerificationDocumentException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("QR scan skipped: {}", e.getMessage());
                extracted.put("qr_verified", false);
            }
        }

        session.setCccdAttachmentUids(new ArrayList<>(uidStrings(attachments)));
        session.setCccdExtracted(extracted);
        session.setCccdStatus(DocumentStepStatus.EXTRACTED);
        session = store.save(session);
        return new AnalyzeCccdResponse(session.getId(), CccdExtracted.of(extracted));
    }

    public void confirmCccd(Long userId) {
        ChefVerificationSession session = store.getOrCreate(userId);
        if (session.getCccdStatus() != DocumentStepStatus.EXTRACTED) {
            throw new DocumentNotReadyToConfirmException();
        }
        session.setCccdConfirmed(session.getCccdExtracted());
        session.setCccdStatus(DocumentStepStatus.CONFIRMED);
        store.save(session);
    }

    // ── Business license ─────────────────────────────────────────────────────

    public AnalyzeBusinessResponse analyzeBusiness(Long userId, List<UUID> attachmentUids) {
        ChefVerificationSession session = store.getOrCreate(userId);
        List<Attachment> attachments = completedAttachments(attachmentUids);

        Analysis result = gemini.analyzeBusinessLicense(urls(attachments));
        if (!result.errors().isEmpty()) {
            throw new VerificationDocumentException(errorsOf(result));
        }
        session.setBusinessAttachmentUids(new ArrayList<>(uidStrings(attachments)));
        session.setBusinessExtracted(result.extracted());
        session.setBusinessStatus(DocumentStepStatus.EXTRACTED);
        session = store.save(session);
        return new AnalyzeBusinessResponse(session.getId(), BusinessExtracted.of(result.extracted()));
    }

    public void confirmBusiness(Long userId) {
        ChefVerificationSession session = store.getOrCreate(userId);
        if (session.getBusinessStatus() != DocumentStepStatus.EXTRACTED) {
            throw new DocumentNotReadyToConfirmException();
        }
        session.setBusinessConfirmed(session.getBusinessExtracted());
        session.setBusinessStatus(DocumentStepStatus.CONFIRMED);
        store.save(session);
    }

    // ── Food safety ──────────────────────────────────────────────────────────

    public AnalyzeFoodSafetyResponse analyzeFoodSafety(Long userId, List<UUID> attachmentUids) {
        ChefVerificationSession session = store.getOrCreate(userId);
        List<Attachment> attachments = completedAttachments(attachmentUids);

        Analysis result = gemini.analyzeFoodSafety(urls(attachments));
        if (!result.errors().isEmpty()) {
            throw new VerificationDocumentException(errorsOf(result));
        }
        session.setFoodSafetyAttachmentUids(new ArrayList<>(uidStrings(attachments)));
        session.setFoodSafetyExtracted(result.extracted());
        session.setFoodSafetyStatus(DocumentStepStatus.EXTRACTED);
        session = store.save(session);
        return new AnalyzeFoodSafetyResponse(session.getId(), FoodSafetyExtracted.of(result.extracted()));
    }

    public void confirmFoodSafety(Long userId) {
        ChefVerificationSession session = store.getOrCreate(userId);
        if (session.getFoodSafetyStatus() != DocumentStepStatus.EXTRACTED) {
            throw new DocumentNotReadyToConfirmException();
        }
        session.setFoodSafetyConfirmed(session.getFoodSafetyExtracted());
        session.setFoodSafetyStatus(DocumentStepStatus.CONFIRMED);
        store.save(session);
    }

    // ── Cross validation ─────────────────────────────────────────────────────

    private static String normalize(String text) {
        return RemoveAccents.apply(text.strip().toLowerCase());
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return truthy(v) ? v.toString() : "";
    }

    private static Map<String, Object> error(String code, String message, String... documents) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        e.put("documents", List.of(documents));
        return e;
    }

    /**
     * All three documents must be CONFIRMED. Checks: CCCD name == business owner; business owner ==
     * food-safety owner; business address ~ food-safety address (Gemini, fail-safe = match);
     * food-safety cert not expired. Failure: persists passed=false + errors THEN raises
     * CrossValidationFailed; success: status AWAITING_SELFIE.
     */
    public Map<String, Object> crossValidate(Long userId) {
        ChefVerificationSession session = store.getOrCreate(userId);
        if (!session.allDocumentsConfirmed()) {
            throw new CrossValidationNotReadyException();
        }
        Map<String, Object> cccd = session.getCccdConfirmed() == null ? Map.of() : session.getCccdConfirmed();
        Map<String, Object> business = session.getBusinessConfirmed() == null ? Map.of() : session.getBusinessConfirmed();
        Map<String, Object> foodSafety = session.getFoodSafetyConfirmed() == null ? Map.of() : session.getFoodSafetyConfirmed();

        List<Map<String, Object>> errors = new ArrayList<>();

        String cccdName = text(cccd, "full_name");
        String bizName = text(business, "owner_name");
        if (!cccdName.isEmpty() && !bizName.isEmpty() && !normalize(cccdName).equals(normalize(bizName))) {
            errors.add(error("OWNER_NAME_MISMATCH_CCCD_BUSINESS",
                    "Thông tin chủ sở hữu trên giấy đăng ký kinh doanh không khớp "
                            + "với thông tin trên CCCD. Vui lòng kiểm tra lại giấy tờ đã tải lên.",
                    "cccd", "business"));
        }

        String fsName = text(foodSafety, "owner_name");
        if (!bizName.isEmpty() && !fsName.isEmpty() && !normalize(bizName).equals(normalize(fsName))) {
            errors.add(error("OWNER_NAME_MISMATCH_BUSINESS_FOOD_SAFETY",
                    "Thông tin chủ sở hữu trên giấy chứng nhận an toàn thực phẩm "
                            + "không khớp với giấy đăng ký kinh doanh.",
                    "business", "food_safety"));
        }

        String bizAddr = text(business, "address");
        String fsAddr = text(foodSafety, "address");
        if (!bizAddr.isEmpty() && !fsAddr.isEmpty() && !gemini.verifySameAddress(bizAddr, fsAddr)) {
            errors.add(error("ADDRESS_MISMATCH_BUSINESS_FOOD_SAFETY",
                    "Địa chỉ trên giấy chứng nhận an toàn thực phẩm không khớp "
                            + "với địa chỉ trên giấy đăng ký kinh doanh.",
                    "business", "food_safety"));
        }

        Object expiry = foodSafety.get("expiry_date");
        if (truthy(expiry)) {
            try {
                if (LocalDate.parse(expiry.toString()).isBefore(LocalDate.now())) {
                    errors.add(error("FOOD_SAFETY_CERT_EXPIRED",
                            "Giấy chứng nhận an toàn thực phẩm đã hết hạn.", "food_safety"));
                }
            } catch (DateTimeParseException ignored) {
                // Django: (ValueError, TypeError) -> pass
            }
        }

        if (!errors.isEmpty()) {
            session.setCrossValidationPassed(false);
            session.setCrossValidationErrors(errors);
            store.save(session);
            throw new CrossValidationFailedException(errors);
        }

        session.setCrossValidationPassed(true);
        session.setCrossValidationErrors(new ArrayList<>());
        session.setStatus(VerificationSessionStatus.AWAITING_SELFIE);
        store.save(session);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("passed", true);
        ok.put("next_step", "SELFIE");
        return ok;
    }

    // ── Selfie + face matching + decision ────────────────────────────────────

    /** Generates a fresh VERIFY-xxxxxx code (TTL 10 min) and persists it. */
    public VerificationCodeResponse requestSelfieCode(Long userId) {
        ChefVerificationSession session = store.getOrCreate(userId);
        String code = session.generateVerificationCode(Instant.now());
        session = store.save(session);
        return new VerificationCodeResponse(code, iso(session.getVerificationCodeExpiresAt()));
    }

    public ChefVerificationSession analyzeSelfie(Long userId, UUID attachmentUid) {
        ChefVerificationSession session = store.getOrCreate(userId);

        if (session.getStatus() == VerificationSessionStatus.COMPLETED) {
            throw new VerificationAlreadyCompletedException();
        }
        if (!session.verificationCodeIsValid(Instant.now())) {
            throw new SelfieCodeExpiredException();
        }
        Attachment attachment = attachmentService.handleAttachment(attachmentUid);

        // Step 1: Gemini reads the selfie
        Map<String, Object> selfie = gemini.analyzeSelfie(attachment.getPublicUrl(), session.getVerificationCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> selfieErrors = (List<Map<String, Object>>) selfie.get("errors");
        if (!selfieErrors.isEmpty()) {
            throw new VerificationDocumentException(selfieErrors);
        }

        // Step 2: the code read must match
        Object read = selfie.get("verification_code_read");
        String codeRead = (truthy(read) ? read.toString() : "").strip().toUpperCase();
        String expected = session.getVerificationCode().strip().toUpperCase();
        if (!codeRead.equals(expected)) {
            throw new SelfieCodeMismatchException();
        }

        session.setSelfieAttachmentUid(attachment.getUid());
        session.setSelfieExtracted(selfie);
        session = store.save(session);

        // Step 3: face matching against the first CCCD image (front); any failure -> unknown
        Double similarity = null;
        try {
            String primaryUid = session.getCccdAttachmentUids().isEmpty() ? null : session.getCccdAttachmentUids().get(0);
            if (primaryUid != null) {
                Optional<Attachment> cccdAttachment = attachmentRepository
                        .findByUidAndIsDeletedFalseAndIsFileDeletedFalse(UUID.fromString(primaryUid));
                if (cccdAttachment.isPresent()) {
                    byte[] cccdBytes = imageFetcher.fetch(cccdAttachment.get().getPublicUrl()).data();
                    byte[] selfieBytes = imageFetcher.fetch(attachment.getPublicUrl()).data();
                    similarity = faceMatcher.compare(cccdBytes, selfieBytes).orElse(null);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Face matching skipped due to error: {}", e.getMessage());
        }
        session.setFaceSimilarityScore(similarity);

        // Step 4: risk engine
        List<String> flags = RiskEngine.collectRiskFlags(session, LocalDate.now());
        int score = RiskEngine.calculateRiskScore(flags);
        session.setRiskFlags(new ArrayList<>(flags));
        session.setRiskScore(score);
        session.setDecision(RiskEngine.makeDecision(score));
        session.setStatus(VerificationSessionStatus.COMPLETED);
        session = store.save(session);
        Long id = session.getId();

        // Step 5: sensitive-image deletion first (highest priority, isolated), then safe identity +
        // certificates - each may fail without affecting the others (Django try/except + log).
        try {
            finalizer.deleteSensitiveImages(id);
        } catch (RuntimeException e) {
            log.error("S3 sensitive image deletion failed: {}", e.getMessage(), e);
        }
        try {
            finalizer.storeSafeIdentity(id);
            try {
                finalizer.createBusinessCertificate(id);
            } catch (RuntimeException e) {
                log.error("Failed to create Business Certificate: {}", e.getMessage());
            }
            try {
                finalizer.createFoodSafetyCertificate(id);
            } catch (RuntimeException e) {
                log.error("Failed to create Food Safety Certificate: {}", e.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("finalize_verification failed: {}", e.getMessage(), e);
        }
        return session;
    }

    // ── Status ───────────────────────────────────────────────────────────────

    public SessionStatusResponse getStatus(Long userId) {
        ChefVerificationSession session = store.require(userId);
        String selfieUrl = null;
        if ("PENDING_REVIEW".equals(session.getDecision()) && session.getSelfieAttachmentUid() != null) {
            selfieUrl = attachmentRepository.findById(session.getSelfieAttachmentUid())
                    .map(Attachment::getPublicUrl).orElse(null);
        }
        VerifiedIdentity identity = null;
        if (session.getVerifiedIdentity() != null) {
            Map<String, Object> vi = session.getVerifiedIdentity();
            identity = new VerifiedIdentity(vi.get("full_name") == null ? null : vi.get("full_name").toString(),
                    vi.get("date_of_birth") == null ? null : vi.get("date_of_birth").toString());
        }
        return new SessionStatusResponse(
                session.getStatus().name(), session.getCccdStatus().name(), session.getBusinessStatus().name(),
                session.getFoodSafetyStatus().name(), session.getCrossValidationPassed(), session.getDecision(),
                session.getRiskScore(), session.getRiskFlags() == null ? List.of() : session.getRiskFlags(),
                session.getFaceSimilarityScore(), session.getCccdNumberMasked(), identity,
                session.getVerifiedAt() == null ? null : iso(session.getVerifiedAt()), selfieUrl);
    }
}
