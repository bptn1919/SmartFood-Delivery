package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.exception.AttachmentIsNotCompletedException;
import com.amomeal.marketplace.attachment.exception.AttachmentNotFoundException;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.verification.dto.VerificationResponses.*;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.entity.DocumentStepStatus;
import com.amomeal.marketplace.verification.entity.VerificationSessionStatus;
import com.amomeal.marketplace.verification.exception.*;
import com.amomeal.marketplace.verification.gemini.GeminiVisionService;
import com.amomeal.marketplace.verification.gemini.GeminiVisionService.Analysis;
import com.amomeal.marketplace.verification.gemini.ImageFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Every branch of VerificationService with mocked collaborators (no Spring, no DB, no Gemini). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificationServiceTest {

    private static final Long USER = 7L;

    @Mock private VerificationSessionStore store;
    @Mock private AttachmentService attachmentService;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private GeminiVisionService gemini;
    @Mock private ImageFetcher fetcher;
    @Mock private CccdQrScanner qr;
    @Mock private FaceMatcher faceMatcher;
    @Mock private VerificationFinalizer finalizer;

    private VerificationService service;
    private ChefVerificationSession session;

    @BeforeEach
    void setUp() {
        service = new VerificationService(store, attachmentService, attachmentRepository, gemini, fetcher, qr,
                faceMatcher, finalizer);
        session = new ChefVerificationSession();
        session.setId(11L);
        session.setUserId(USER);
        when(store.getOrCreate(USER)).thenReturn(session);
        when(store.require(USER)).thenReturn(session);
        when(store.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Attachment attachment(String url) {
        return Attachment.builder().uid(UUID.randomUUID()).publicUrl(url).isCompleted(true).build();
    }

    private Attachment stubAttachment(String url) {
        Attachment a = attachment(url);
        when(attachmentService.handleAttachment(a.getUid())).thenReturn(a);
        return a;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<Map<String, Object>> errors(String... codes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : codes) {
            out.add(map("code", c, "message", "m-" + c));
        }
        return out;
    }

    // ── CCCD ─────────────────────────────────────────────────────────────────

    private Analysis cccdOk() {
        return new Analysis(map("full_name", "Nguyễn Văn A", "cccd_number", "079123456789",
                "date_of_birth", "1990-01-02", "address", "1 Lê Lợi", "image_clear", true), List.of());
    }

    @Test
    void analyzeCccd_success_withoutQr_marksExtractedAndStoresUidsInOrder() {
        Attachment front = stubAttachment("http://x/f");
        Attachment back = stubAttachment("http://x/b");
        when(gemini.analyzeCccd(List.of("http://x/f", "http://x/b"))).thenReturn(cccdOk());
        when(fetcher.fetch("http://x/b")).thenReturn(new ImageFetcher.FetchedImage(new byte[]{1}, "image/jpeg"));
        when(qr.scan(any())).thenReturn(Optional.empty());

        AnalyzeCccdResponse r = service.analyzeCccd(USER, List.of(front.getUid(), back.getUid()));

        assertThat(r.sessionId()).isEqualTo(11L);
        assertThat(r.extracted().fullName()).isEqualTo("Nguyễn Văn A");
        assertThat(r.extracted().cccdNumber()).isEqualTo("079123456789");
        assertThat(session.getCccdStatus()).isEqualTo(DocumentStepStatus.EXTRACTED);
        assertThat(session.getCccdAttachmentUids()).containsExactly(front.getUid().toString(), back.getUid().toString());
        assertThat(session.getCccdExtracted()).containsEntry("qr_verified", false);
        verify(store).save(session);
    }

    @Test
    void analyzeCccd_geminiErrors_raiseDocumentErrorWithDetail_andDoNotSave() {
        Attachment front = stubAttachment("http://x/f");
        Attachment back = stubAttachment("http://x/b");
        when(gemini.analyzeCccd(any())).thenReturn(new Analysis(null, errors("IMAGE_BLURRY")));

        assertThatThrownBy(() -> service.analyzeCccd(USER, List.of(front.getUid(), back.getUid())))
                .isInstanceOfSatisfying(VerificationDocumentException.class, e -> {
                    assertThat(e.getMessageCode()).isEqualTo("DOCUMENT_ANALYSIS_ERROR");
                    assertThat(e.getHttpStatus().value()).isEqualTo(422);
                    assertThat(e.getDetail()).isEqualTo(errors("IMAGE_BLURRY"));
                });
        verify(store, never()).save(any());
        assertThat(session.getCccdStatus()).isEqualTo(DocumentStepStatus.PENDING);
    }

    @Test
    void analyzeCccd_attachmentMissingOrNotCompleted_propagates_sessionStillCreated() {
        UUID missing = UUID.randomUUID();
        UUID notDone = UUID.randomUUID();
        when(attachmentService.handleAttachment(missing)).thenThrow(new AttachmentNotFoundException());
        when(attachmentService.handleAttachment(notDone)).thenThrow(new AttachmentIsNotCompletedException("x"));

        assertThatThrownBy(() -> service.analyzeCccd(USER, List.of(missing, UUID.randomUUID())))
                .isInstanceOf(AttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.analyzeCccd(USER, List.of(notDone, UUID.randomUUID())))
                .isInstanceOf(AttachmentIsNotCompletedException.class);
        verify(store, times(2)).getOrCreate(USER); // Django get_or_create runs first and is never rolled back
        verifyNoInteractions(gemini);
    }

    @Test
    void analyzeCccd_qrNumberMismatch_isRejectedAndNothingSaved() {
        Attachment front = stubAttachment("http://x/f");
        Attachment back = stubAttachment("http://x/b");
        when(gemini.analyzeCccd(any())).thenReturn(cccdOk());
        when(fetcher.fetch("http://x/b")).thenReturn(new ImageFetcher.FetchedImage(new byte[]{1}, "image/jpeg"));
        when(qr.scan(any())).thenReturn(Optional.of(map("cccd_number", "000000000000")));

        assertThatThrownBy(() -> service.analyzeCccd(USER, List.of(front.getUid(), back.getUid())))
                .isInstanceOfSatisfying(VerificationDocumentException.class, e ->
                        assertThat(e.getDetail().toString()).contains("CCCD_QR_NUMBER_MISMATCH"));
        verify(store, never()).save(any());
    }

    @Test
    void analyzeCccd_qrMatch_recordsQrFields() {
        Attachment front = stubAttachment("http://x/f");
        Attachment back = stubAttachment("http://x/b");
        when(gemini.analyzeCccd(any())).thenReturn(cccdOk());
        when(fetcher.fetch("http://x/b")).thenReturn(new ImageFetcher.FetchedImage(new byte[]{1}, "image/jpeg"));
        when(qr.scan(any())).thenReturn(Optional.of(map("cccd_number", "079123456789", "gender", "Nam", "old_cmnd", "123")));

        service.analyzeCccd(USER, List.of(front.getUid(), back.getUid()));

        assertThat(session.getCccdExtracted()).containsEntry("qr_verified", true).containsEntry("qr_gender", "Nam")
                .containsEntry("old_cmnd", "123");
    }

    @Test
    void analyzeCccd_qrScannerOrFetchFailure_isSwallowed_qrNotVerified() {
        Attachment front = stubAttachment("http://x/f");
        Attachment back = stubAttachment("http://x/b");
        when(gemini.analyzeCccd(any())).thenReturn(cccdOk());
        when(fetcher.fetch(anyString())).thenThrow(new IllegalStateException("network"));

        service.analyzeCccd(USER, List.of(front.getUid(), back.getUid()));

        assertThat(session.getCccdStatus()).isEqualTo(DocumentStepStatus.EXTRACTED);
        assertThat(session.getCccdExtracted()).containsEntry("qr_verified", false);
    }

    @Test
    void analyzeCccd_qrPresentButWithoutNumber_isNotVerified() {
        Attachment front = stubAttachment("http://x/f");
        Attachment back = stubAttachment("http://x/b");
        when(gemini.analyzeCccd(any())).thenReturn(cccdOk());
        when(fetcher.fetch("http://x/b")).thenReturn(new ImageFetcher.FetchedImage(new byte[]{1}, "image/jpeg"));
        when(qr.scan(any())).thenReturn(Optional.of(map("gender", "Nu")));

        service.analyzeCccd(USER, List.of(front.getUid(), back.getUid()));

        assertThat(session.getCccdExtracted()).containsEntry("qr_verified", false).containsEntry("qr_gender", "Nu");
    }

    @Test
    void confirmCccd_onlyFromExtracted() {
        assertThatThrownBy(() -> service.confirmCccd(USER)).isInstanceOf(DocumentNotReadyToConfirmException.class);
        session.setCccdStatus(DocumentStepStatus.EXTRACTED);
        session.setCccdExtracted(map("full_name", "A"));
        service.confirmCccd(USER);
        assertThat(session.getCccdStatus()).isEqualTo(DocumentStepStatus.CONFIRMED);
        assertThat(session.getCccdConfirmed()).containsEntry("full_name", "A");
        // already confirmed -> not ready again
        assertThatThrownBy(() -> service.confirmCccd(USER)).isInstanceOf(DocumentNotReadyToConfirmException.class)
                .hasMessageContaining("đã được xác nhận rồi");
    }

    // ── Business / food safety ───────────────────────────────────────────────

    @Test
    void business_analyzeThenConfirm_lifecycle() {
        Attachment p1 = stubAttachment("http://x/1");
        Attachment p2 = stubAttachment("http://x/2");
        when(gemini.analyzeBusinessLicense(List.of("http://x/1", "http://x/2")))
                .thenReturn(new Analysis(map("owner_name", "A", "business_name", "Quán", "business_license_number", "9",
                        "address", "x", "issue_date", "2020-01-01"), List.of()));

        AnalyzeBusinessResponse r = service.analyzeBusiness(USER, List.of(p1.getUid(), p2.getUid()));
        assertThat(r.extracted().businessName()).isEqualTo("Quán");
        assertThat(session.getBusinessStatus()).isEqualTo(DocumentStepStatus.EXTRACTED);
        assertThat(session.getBusinessAttachmentUids()).hasSize(2);

        service.confirmBusiness(USER);
        assertThat(session.getBusinessStatus()).isEqualTo(DocumentStepStatus.CONFIRMED);
        assertThat(session.getBusinessConfirmed()).containsEntry("owner_name", "A");
    }

    @Test
    void business_errors_andNotReady() {
        Attachment p1 = stubAttachment("http://x/1");
        when(gemini.analyzeBusinessLicense(any())).thenReturn(new Analysis(null, errors("WRONG_DOCUMENT_TYPE")));
        assertThatThrownBy(() -> service.analyzeBusiness(USER, List.of(p1.getUid())))
                .isInstanceOf(VerificationDocumentException.class);
        assertThatThrownBy(() -> service.confirmBusiness(USER)).isInstanceOf(DocumentNotReadyToConfirmException.class);
        verify(store, never()).save(any());
    }

    @Test
    void foodSafety_analyzeThenConfirm_lifecycle() {
        Attachment p1 = stubAttachment("http://x/1");
        when(gemini.analyzeFoodSafety(any())).thenReturn(new Analysis(map("owner_name", "A", "facility_name", "Bếp",
                "certificate_number", "5", "address", "x", "issue_date", "2024-01-01", "expiry_date", "2099-01-01"), List.of()));

        AnalyzeFoodSafetyResponse r = service.analyzeFoodSafety(USER, List.of(p1.getUid()));
        assertThat(r.extracted().expiryDate()).isEqualTo("2099-01-01");
        assertThat(session.getFoodSafetyStatus()).isEqualTo(DocumentStepStatus.EXTRACTED);

        service.confirmFoodSafety(USER);
        assertThat(session.getFoodSafetyStatus()).isEqualTo(DocumentStepStatus.CONFIRMED);
    }

    @Test
    void foodSafety_errors_andNotReady() {
        Attachment p1 = stubAttachment("http://x/1");
        when(gemini.analyzeFoodSafety(any())).thenReturn(new Analysis(null, errors("IMAGE_BLURRY")));
        assertThatThrownBy(() -> service.analyzeFoodSafety(USER, List.of(p1.getUid())))
                .isInstanceOf(VerificationDocumentException.class);
        assertThatThrownBy(() -> service.confirmFoodSafety(USER)).isInstanceOf(DocumentNotReadyToConfirmException.class);
    }

    // ── Cross validation ─────────────────────────────────────────────────────

    private void confirmAll(String cccdName, String bizOwner, String fsOwner, String bizAddr, String fsAddr, String expiry) {
        session.setCccdStatus(DocumentStepStatus.CONFIRMED);
        session.setBusinessStatus(DocumentStepStatus.CONFIRMED);
        session.setFoodSafetyStatus(DocumentStepStatus.CONFIRMED);
        session.setCccdConfirmed(map("full_name", cccdName));
        session.setBusinessConfirmed(map("owner_name", bizOwner, "address", bizAddr));
        session.setFoodSafetyConfirmed(map("owner_name", fsOwner, "address", fsAddr, "expiry_date", expiry));
    }

    @Test
    void crossValidate_notReady_whenAnyDocumentUnconfirmed() {
        session.setCccdStatus(DocumentStepStatus.CONFIRMED);
        session.setBusinessStatus(DocumentStepStatus.EXTRACTED);
        assertThatThrownBy(() -> service.crossValidate(USER)).isInstanceOf(CrossValidationNotReadyException.class);
        verify(store, never()).save(any());
    }

    @Test
    void crossValidate_pass_movesToAwaitingSelfie_accentAndCaseInsensitive() {
        confirmAll("Nguyễn Văn A", "  NGUYEN VAN a ", "nguyen van a", "1 Lê Lợi", "1 Le Loi", "2099-01-01");
        when(gemini.verifySameAddress(anyString(), anyString())).thenReturn(true);

        Map<String, Object> r = service.crossValidate(USER);

        assertThat(r).containsEntry("passed", true).containsEntry("next_step", "SELFIE");
        assertThat(session.getStatus()).isEqualTo(VerificationSessionStatus.AWAITING_SELFIE);
        assertThat(session.getCrossValidationPassed()).isTrue();
        assertThat(session.getCrossValidationErrors()).isEmpty();
    }

    @Test
    void crossValidate_allFourFailures_persistedThenRaisedWithDocumentsDetail() {
        confirmAll("Nguyen A", "Tran B", "Le C", "1 Lê Lợi", "99 Hai Ba Trung", "2000-01-01");
        when(gemini.verifySameAddress(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.crossValidate(USER)).isInstanceOfSatisfying(CrossValidationFailedException.class, e -> {
            assertThat(e.getMessageCode()).isEqualTo("CROSS_VALIDATION_FAILED");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> detail = (List<Map<String, Object>>) e.getDetail();
            assertThat(detail).extracting(d -> d.get("code")).containsExactly(
                    "OWNER_NAME_MISMATCH_CCCD_BUSINESS", "OWNER_NAME_MISMATCH_BUSINESS_FOOD_SAFETY",
                    "ADDRESS_MISMATCH_BUSINESS_FOOD_SAFETY", "FOOD_SAFETY_CERT_EXPIRED");
            assertThat(detail.get(0)).containsEntry("documents", List.of("cccd", "business"));
            assertThat(detail.get(1)).containsEntry("documents", List.of("business", "food_safety"));
            assertThat(detail.get(3)).containsEntry("documents", List.of("food_safety"));
        });
        // persist-then-raise: the failure was saved BEFORE the exception
        assertThat(session.getCrossValidationPassed()).isFalse();
        assertThat(session.getCrossValidationErrors()).hasSize(4);
        assertThat(session.getStatus()).isEqualTo(VerificationSessionStatus.IN_PROGRESS);
        verify(store).save(session);
    }

    @Test
    void crossValidate_singleNameMismatch_onlyBusinessVsCccd() {
        confirmAll("Nguyen A", "Tran B", "Tran B", "x", "x", "2099-01-01");
        when(gemini.verifySameAddress(anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.crossValidate(USER)).isInstanceOfSatisfying(CrossValidationFailedException.class,
                e -> assertThat(e.getDetail().toString()).contains("OWNER_NAME_MISMATCH_CCCD_BUSINESS")
                        .doesNotContain("BUSINESS_FOOD_SAFETY"));
    }

    @Test
    void crossValidate_missingFieldsAreSkipped_andUnparseableExpiryIgnored() {
        confirmAll("Nguyen A", null, "", null, "", "not-a-date");
        service.crossValidate(USER);
        assertThat(session.getCrossValidationPassed()).isTrue();
        verify(gemini, never()).verifySameAddress(anyString(), anyString());
    }

    @Test
    void crossValidate_expiringTodayIsStillValid() {
        confirmAll("A", "A", "A", "x", "x", LocalDate.now().toString());
        when(gemini.verifySameAddress(anyString(), anyString())).thenReturn(true);
        service.crossValidate(USER);
        assertThat(session.getCrossValidationPassed()).isTrue();
    }

    // ── Selfie code ──────────────────────────────────────────────────────────

    @Test
    void requestSelfieCode_generatesVerifyCodeWithTenMinuteTtl() {
        Instant before = Instant.now();
        VerificationCodeResponse r = service.requestSelfieCode(USER);

        assertThat(r.verificationCode()).matches("VERIFY-[1-9]\\d{5}");
        assertThat(session.getVerificationCode()).isEqualTo(r.verificationCode());
        assertThat(Duration.between(before, session.getVerificationCodeExpiresAt()))
                .isBetween(Duration.ofMinutes(10), Duration.ofMinutes(10).plusSeconds(5));
        assertThat(r.expiresAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+00:00");
        // asking again replaces the code
        String first = r.verificationCode();
        for (int i = 0; i < 20 && first.equals(service.requestSelfieCode(USER).verificationCode()); i++) {
            // extremely unlikely collision; loop ends as soon as it differs
        }
        verify(store, atLeast(2)).save(session);
    }

    @Test
    void verificationCodeValidity_rules() {
        ChefVerificationSession s = new ChefVerificationSession();
        Instant now = Instant.now();
        assertThat(s.verificationCodeIsValid(now)).isFalse(); // never generated
        s.setVerificationCode("VERIFY-111111");
        assertThat(s.verificationCodeIsValid(now)).isFalse(); // no expiry
        s.setVerificationCodeExpiresAt(now.plusSeconds(1));
        assertThat(s.verificationCodeIsValid(now)).isTrue();
        assertThat(s.verificationCodeIsValid(now.plusSeconds(2))).isFalse();
        s.setVerificationCode("");
        assertThat(s.verificationCodeIsValid(now)).isFalse();
    }

    // ── Selfie analysis ──────────────────────────────────────────────────────

    private void readyForSelfie(String code) {
        session.setStatus(VerificationSessionStatus.AWAITING_SELFIE);
        session.setVerificationCode(code);
        session.setVerificationCodeExpiresAt(Instant.now().plusSeconds(300));
        session.setCccdConfirmed(map("full_name", "A", "cccd_number", "079123456789", "image_clear", true));
        session.setBusinessConfirmed(map("image_clear", true, "has_signature", true, "has_red_stamp", true));
        session.setFoodSafetyConfirmed(map("image_clear", true, "has_signature", true, "has_red_stamp", true,
                "expiry_date", "2099-01-01"));
    }

    private Map<String, Object> selfieResult(String codeRead, List<Map<String, Object>> errs) {
        return map("face_detected", true, "has_id_card", true, "has_verification_code", true,
                "verification_code_read", codeRead, "errors", errs);
    }

    @Test
    void analyzeSelfie_alreadyCompleted_isConflict_beforeAnythingElse() {
        session.setStatus(VerificationSessionStatus.COMPLETED);
        assertThatThrownBy(() -> service.analyzeSelfie(USER, UUID.randomUUID()))
                .isInstanceOfSatisfying(VerificationAlreadyCompletedException.class,
                        e -> assertThat(e.getHttpStatus().value()).isEqualTo(409));
        verifyNoInteractions(attachmentService, gemini);
    }

    @Test
    void analyzeSelfie_noCodeOrExpiredCode_isSelfieCodeExpired() {
        assertThatThrownBy(() -> service.analyzeSelfie(USER, UUID.randomUUID())).isInstanceOf(SelfieCodeExpiredException.class);
        session.setVerificationCode("VERIFY-123456");
        session.setVerificationCodeExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.analyzeSelfie(USER, UUID.randomUUID())).isInstanceOf(SelfieCodeExpiredException.class);
        verifyNoInteractions(gemini);
    }

    @Test
    void analyzeSelfie_attachmentErrorsPropagate() {
        readyForSelfie("VERIFY-123456");
        UUID uid = UUID.randomUUID();
        when(attachmentService.handleAttachment(uid)).thenThrow(new AttachmentNotFoundException());
        assertThatThrownBy(() -> service.analyzeSelfie(USER, uid)).isInstanceOf(AttachmentNotFoundException.class);
        doThrow(new AttachmentIsNotCompletedException("x")).when(attachmentService).handleAttachment(uid);
        assertThatThrownBy(() -> service.analyzeSelfie(USER, uid)).isInstanceOf(AttachmentIsNotCompletedException.class);
    }

    @Test
    void analyzeSelfie_geminiDocumentErrors_areRaised() {
        readyForSelfie("VERIFY-123456");
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie("http://x/s", "VERIFY-123456")).thenReturn(selfieResult(null, errors("FACE_NOT_DETECTED")));
        assertThatThrownBy(() -> service.analyzeSelfie(USER, selfie.getUid()))
                .isInstanceOfSatisfying(VerificationDocumentException.class,
                        e -> assertThat(e.getDetail()).isEqualTo(errors("FACE_NOT_DETECTED")));
        verify(store, never()).save(any());
    }

    @Test
    void analyzeSelfie_codeMismatch_isUnprocessable_andNothingPersisted() {
        readyForSelfie("VERIFY-123456");
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("VERIFY-999999", List.of()));
        assertThatThrownBy(() -> service.analyzeSelfie(USER, selfie.getUid())).isInstanceOfSatisfying(
                SelfieCodeMismatchException.class, e -> assertThat(e.getHttpStatus().value()).isEqualTo(422));

        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult(null, List.of()));
        assertThatThrownBy(() -> service.analyzeSelfie(USER, selfie.getUid())).isInstanceOf(SelfieCodeMismatchException.class);
        verify(store, never()).save(any());
        assertThat(session.getStatus()).isEqualTo(VerificationSessionStatus.AWAITING_SELFIE);
    }

    @Test
    void analyzeSelfie_success_caseAndWhitespaceInsensitive_faceMatchDrivesDecision_thenFinalizesInOrder() {
        readyForSelfie("VERIFY-123456");
        Attachment cccdFront = attachment("http://x/cccd-front");
        session.setCccdAttachmentUids(new ArrayList<>(List.of(cccdFront.getUid().toString())));
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("  verify-123456 ", List.of()));
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(cccdFront.getUid()))
                .thenReturn(Optional.of(cccdFront));
        when(fetcher.fetch(anyString())).thenAnswer(i -> new ImageFetcher.FetchedImage(((String) i.getArgument(0)).getBytes(), "image/jpeg"));
        when(faceMatcher.compare(any(), any())).thenReturn(Optional.of(0.91));

        ChefVerificationSession result = service.analyzeSelfie(USER, selfie.getUid());

        assertThat(result.getDecision()).isEqualTo("PENDING_REVIEW");
        assertThat(result.getRiskScore()).isZero();
        assertThat(result.getRiskFlags()).isEmpty();
        assertThat(result.getFaceSimilarityScore()).isEqualTo(0.91);
        assertThat(result.getStatus()).isEqualTo(VerificationSessionStatus.COMPLETED);
        assertThat(result.getSelfieAttachmentUid()).isEqualTo(selfie.getUid());
        assertThat(result.getSelfieExtracted()).containsEntry("verification_code_read", "  verify-123456 ");
        // the CCCD front (index 0) is compared with the selfie
        verify(faceMatcher).compare("http://x/cccd-front".getBytes(), "http://x/s".getBytes());

        InOrder order = inOrder(finalizer);
        order.verify(finalizer).deleteSensitiveImages(11L);
        order.verify(finalizer).storeSafeIdentity(11L);
        order.verify(finalizer).createBusinessCertificate(11L);
        order.verify(finalizer).createFoodSafetyCertificate(11L);
    }

    @Test
    void analyzeSelfie_noFaceScore_flagsFaceNotDetected_plus50_stillPendingReview() {
        readyForSelfie("VERIFY-123456");
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("VERIFY-123456", List.of()));

        ChefVerificationSession result = service.analyzeSelfie(USER, selfie.getUid());

        assertThat(result.getFaceSimilarityScore()).isNull();
        assertThat(result.getRiskFlags()).containsExactly("FACE_NOT_DETECTED");
        assertThat(result.getRiskScore()).isEqualTo(50);
        assertThat(result.getDecision()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void analyzeSelfie_faceMatcherOrFetchFailure_isSwallowed() {
        readyForSelfie("VERIFY-123456");
        Attachment cccdFront = attachment("http://x/cccd-front");
        session.setCccdAttachmentUids(new ArrayList<>(List.of(cccdFront.getUid().toString())));
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("VERIFY-123456", List.of()));
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(cccdFront.getUid()))
                .thenReturn(Optional.of(cccdFront));
        when(fetcher.fetch(anyString())).thenThrow(new IllegalStateException("net"));

        ChefVerificationSession result = service.analyzeSelfie(USER, selfie.getUid());

        assertThat(result.getFaceSimilarityScore()).isNull();
        assertThat(result.getStatus()).isEqualTo(VerificationSessionStatus.COMPLETED);
    }

    @Test
    void analyzeSelfie_lowSimilarityPlusMismatches_isRejected() {
        readyForSelfie("VERIFY-123456");
        session.setCrossValidationErrors(List.of(map("code", "OWNER_NAME_MISMATCH_CCCD_BUSINESS")));
        Attachment cccdFront = attachment("http://x/cccd-front");
        session.setCccdAttachmentUids(new ArrayList<>(List.of(cccdFront.getUid().toString())));
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("VERIFY-123456", List.of()));
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(cccdFront.getUid()))
                .thenReturn(Optional.of(cccdFront));
        when(fetcher.fetch(anyString())).thenReturn(new ImageFetcher.FetchedImage(new byte[]{1}, "image/jpeg"));
        when(faceMatcher.compare(any(), any())).thenReturn(Optional.of(0.3));

        ChefVerificationSession result = service.analyzeSelfie(USER, selfie.getUid());

        assertThat(result.getRiskFlags()).containsExactly("OWNER_NAME_MISMATCH", "FACE_MATCH_FAILED");
        assertThat(result.getRiskScore()).isEqualTo(120);
        assertThat(result.getDecision()).isEqualTo("REJECTED");
    }

    @Test
    void analyzeSelfie_finalizerFailuresNeverBreakTheDecision_andAreIsolated() {
        readyForSelfie("VERIFY-123456");
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("VERIFY-123456", List.of()));
        doThrow(new IllegalStateException("s3 down")).when(finalizer).deleteSensitiveImages(anyLong());
        doThrow(new IllegalStateException("biz boom")).when(finalizer).createBusinessCertificate(anyLong());

        ChefVerificationSession result = service.analyzeSelfie(USER, selfie.getUid());

        assertThat(result.getStatus()).isEqualTo(VerificationSessionStatus.COMPLETED);
        verify(finalizer).storeSafeIdentity(11L);                 // still ran after the S3 failure
        verify(finalizer).createFoodSafetyCertificate(11L);       // still ran after the business failure
    }

    @Test
    void analyzeSelfie_safeIdentityFailure_skipsCertificates() {
        readyForSelfie("VERIFY-123456");
        Attachment selfie = stubAttachment("http://x/s");
        when(gemini.analyzeSelfie(anyString(), anyString())).thenReturn(selfieResult("VERIFY-123456", List.of()));
        doThrow(new IllegalStateException("hash boom")).when(finalizer).storeSafeIdentity(anyLong());

        assertThat(service.analyzeSelfie(USER, selfie.getUid()).getStatus()).isEqualTo(VerificationSessionStatus.COMPLETED);
        verify(finalizer, never()).createBusinessCertificate(anyLong());
        verify(finalizer, never()).createFoodSafetyCertificate(anyLong());
    }

    // ── Status ───────────────────────────────────────────────────────────────

    @Test
    void getStatus_withoutSession_isNotFound() {
        when(store.require(USER)).thenThrow(new VerificationSessionNotFoundException());
        assertThatThrownBy(() -> service.getStatus(USER)).isInstanceOfSatisfying(VerificationSessionNotFoundException.class,
                e -> assertThat(e.getHttpStatus().value()).isEqualTo(404));
    }

    @Test
    void getStatus_pendingReview_exposesSelfieUrlAndSafeIdentity() {
        Attachment selfie = attachment("http://x/selfie");
        session.setDecision("PENDING_REVIEW");
        session.setStatus(VerificationSessionStatus.COMPLETED);
        session.setSelfieAttachmentUid(selfie.getUid());
        session.setCccdNumberMasked("079******789");
        session.setVerifiedIdentity(map("full_name", "A", "date_of_birth", "1990-01-02"));
        session.setVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        session.setRiskFlags(new ArrayList<>(List.of("FACE_NOT_DETECTED")));
        session.setRiskScore(50);
        when(attachmentRepository.findById(selfie.getUid())).thenReturn(Optional.of(selfie));

        SessionStatusResponse r = service.getStatus(USER);

        assertThat(r.selfieUrl()).isEqualTo("http://x/selfie");
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.decision()).isEqualTo("PENDING_REVIEW");
        assertThat(r.cccdNumberMasked()).isEqualTo("079******789");
        assertThat(r.verifiedIdentity()).isEqualTo(new VerifiedIdentity("A", "1990-01-02"));
        assertThat(r.verifiedAt()).isEqualTo("2026-01-01T00:00:00.000000+00:00");
        assertThat(r.riskFlags()).containsExactly("FACE_NOT_DETECTED");
        assertThat(r.cccdStatus()).isEqualTo("PENDING");
    }

    @Test
    void getStatus_rejectedOrNoSelfie_hasNoSelfieUrl() {
        Attachment selfie = attachment("http://x/selfie");
        session.setSelfieAttachmentUid(selfie.getUid());
        session.setDecision("REJECTED");
        when(attachmentRepository.findById(selfie.getUid())).thenReturn(Optional.of(selfie));
        assertThat(service.getStatus(USER).selfieUrl()).isNull();

        session.setDecision("PENDING_REVIEW");
        session.setSelfieAttachmentUid(null);
        SessionStatusResponse r = service.getStatus(USER);
        assertThat(r.selfieUrl()).isNull();
        assertThat(r.verifiedIdentity()).isNull();
        assertThat(r.verifiedAt()).isNull();
        assertThat(r.crossValidationPassed()).isNull();
    }
}
