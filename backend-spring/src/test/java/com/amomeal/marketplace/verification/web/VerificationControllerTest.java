package com.amomeal.marketplace.verification.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.entity.ScheduledS3Deletion;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import com.amomeal.marketplace.verification.repository.ScheduledS3DeletionRepository;
import com.amomeal.marketplace.verification.service.S3DeletionService;
import com.amomeal.marketplace.verification.service.VerificationCertificateReviewHook;
import com.amomeal.marketplace.certificate.service.CertificateReviewHook;
import com.amomeal.marketplace.verification.support.FakeGeminiVisionClient;
import com.amomeal.marketplace.verification.support.FakeImageFetcher;
import com.amomeal.marketplace.verification.support.VerificationTestConfig;
import com.amomeal.marketplace.verification.support.VerificationTestConfig.FakeFaceMatcher;
import com.amomeal.marketplace.verification.support.VerificationTestConfig.RecordingS3ObjectDeleter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack /api/verification flow (real Postgres/Redis, fake Gemini / image fetcher / face
 * matcher / S3), including the end-to-end proof that the certificate review hook seam is closed.
 */
@Import({TestcontainersConfiguration.class, VerificationTestConfig.class})
@SpringBootTest(properties = "app.gemini.api-key=test-key")
@AutoConfigureMockMvc
class VerificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomUserRepository customUserRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private ChefVerificationSessionRepository sessionRepository;
    @Autowired private CertificateRepository certificateRepository;
    @Autowired private CertificateAttachmentRepository certificateAttachmentRepository;
    @Autowired private ScheduledS3DeletionRepository scheduledRepository;
    @Autowired private S3DeletionService s3DeletionService;
    @Autowired private CertificateReviewHook reviewHook;
    @Autowired private FakeGeminiVisionClient gemini;
    @Autowired private FakeImageFetcher fetcher;
    @Autowired private FakeFaceMatcher faceMatcher;
    @Autowired private RecordingS3ObjectDeleter s3;

    private record Account(String token, Long userId) {
    }

    // Gemini payload variants
    private String cccdName = "Nguyễn Văn A";
    private String bizOwner = "NGUYEN VAN A";
    private String bizSignature = "true";
    private String fsOwner = "Nguyễn Văn A";
    private String fsExpiry = "2099-12-31";
    private String addressVerdict = "{\"same_location\": true, \"confidence\": \"high\"}";

    @BeforeEach
    void resetFakes() {
        cccdName = "Nguyễn Văn A";
        bizOwner = "NGUYEN VAN A";
        bizSignature = "true";
        fsOwner = "Nguyễn Văn A";
        fsExpiry = "2099-12-31";
        addressVerdict = "{\"same_location\": true, \"confidence\": \"high\"}";
        gemini.reset();
        fetcher.reset();
        faceMatcher.score = Optional.of(0.92);
        s3.deleted.clear();
        s3.enabled = true;
        gemini.respondWith(this::defaultGemini);
    }

    private String defaultGemini(String p) {
        if (p.contains("mặt TRƯỚC CCCD")) {
            return """
                    {"front": {"is_front_side": true, "full_name": "%s", "cccd_number": "079123456789",
                      "date_of_birth": "1990-01-02", "address": "1 Lê Lợi", "image_clear": true, "possible_editing": false},
                     "back": {"is_back_side": true, "has_qr": true}, "same_document": true, "document_errors": []}
                    """.formatted(cccdName);
        }
        if (p.contains("Giấy đăng ký hộ kinh doanh")) {
            return """
                    {"owner_name": "%s", "business_name": "Bếp Nhà A", "business_license_number": "0123456",
                     "address": "1 Lê Lợi, Q.1", "issue_date": "2020-05-06", "has_signature": %s, "has_red_stamp": true,
                     "document_errors": []}
                    """.formatted(bizOwner, bizSignature);
        }
        if (p.contains("An toàn vệ sinh thực phẩm")) {
            return """
                    {"owner_name": "%s", "facility_name": "Cơ sở A", "certificate_number": "ATTP-1",
                     "address": "1 Le Loi", "issue_date": "2024-01-01", "expiry_date": "%s", "has_signature": true,
                     "has_red_stamp": true, "document_errors": []}
                    """.formatted(fsOwner, fsExpiry);
        }
        if (p.contains("ảnh selfie")) {
            Matcher m = Pattern.compile("Mã xác thực cần đọc: (VERIFY-\\d+)").matcher(p);
            assertThat(m.find()).isTrue();
            return """
                    {"face_detected": true, "has_id_card": true, "has_verification_code": true,
                     "verification_code_read": "%s", "document_errors": []}
                    """.formatted(m.group(1).toLowerCase());
        }
        if (p.contains("So sánh 2 địa chỉ")) {
            return addressVerdict;
        }
        throw new IllegalStateException("unexpected prompt");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Account register(String prefix, UserRole role) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000015"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        customUserRepository.save(user);
        return new Account(extract(json, "access_token"), user.getId());
    }

    private static String extract(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException(field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static String auth(Account a) {
        return "Bearer " + a.token();
    }

    private Attachment attachment(String name, boolean completed) {
        return attachmentRepository.save(Attachment.builder().type(AttachmentType.CERTIFICATE).originalName(name)
                .hashedName(UUID.randomUUID() + ".jpg").size(10).contentType("image/jpeg").bucket("kyc-bucket")
                .publicUrl("https://cdn.example.test/" + name).isCompleted(completed).build());
    }

    private ResultActions postJson(Account a, String path, String body) throws Exception {
        return mockMvc.perform(post("/api/verification" + path).header("Authorization", auth(a))
                .contentType("application/json").content(body));
    }

    private ResultActions postNoBody(Account a, String path) throws Exception {
        return mockMvc.perform(post("/api/verification" + path).header("Authorization", auth(a)));
    }

    private ResultActions getPath(Account a, String path) throws Exception {
        return mockMvc.perform(get("/api/verification" + path).header("Authorization", auth(a)));
    }

    private static String uids(Attachment... atts) {
        StringBuilder sb = new StringBuilder("{\"attachment_uids\":[");
        for (int i = 0; i < atts.length; i++) {
            sb.append(i > 0 ? "," : "").append('"').append(atts[i].getUid()).append('"');
        }
        return sb.append("]}").toString();
    }

    private record Kit(Attachment front, Attachment back, Attachment biz1, Attachment biz2, Attachment fs, Attachment selfie) {
    }

    private Kit newKit() {
        return new Kit(attachment("cccd-front.jpg", true), attachment("cccd-back.jpg", true),
                attachment("biz-1.jpg", true), attachment("biz-2.jpg", true), attachment("fs-1.jpg", true),
                attachment("selfie.jpg", true));
    }

    /** analyze + confirm all three documents. */
    private void confirmAllDocuments(Account chef, Kit kit) throws Exception {
        postJson(chef, "/cccd/analyze", uids(kit.front(), kit.back())).andExpect(status().isOk());
        postNoBody(chef, "/cccd/confirm").andExpect(status().isOk());
        postJson(chef, "/business/analyze", uids(kit.biz1(), kit.biz2())).andExpect(status().isOk());
        postNoBody(chef, "/business/confirm").andExpect(status().isOk());
        postJson(chef, "/food-safety/analyze", uids(kit.fs())).andExpect(status().isOk());
        postNoBody(chef, "/food-safety/confirm").andExpect(status().isOk());
    }

    private void passCrossValidationAndSubmitSelfie(Account chef, Kit kit) throws Exception {
        postNoBody(chef, "/cross-validate").andExpect(status().isOk());
        getPath(chef, "/selfie/code").andExpect(status().isOk());
    }

    private ChefVerificationSession session(Account chef) {
        return sessionRepository.findByUserId(chef.userId()).orElseThrow();
    }

    // ── role gating ──────────────────────────────────────────────────────────

    @Test
    void everyEndpointIsChefOnly() throws Exception {
        Account customer = register("kyc-cust", UserRole.CUSTOMER);
        Account admin = register("kyc-admin", UserRole.ADMIN);
        Kit kit = newKit();
        for (Account a : List.of(customer, admin)) {
            postJson(a, "/cccd/analyze", uids(kit.front(), kit.back())).andExpect(status().isUnauthorized());
            postNoBody(a, "/cccd/confirm").andExpect(status().isUnauthorized());
            postJson(a, "/business/analyze", uids(kit.biz1())).andExpect(status().isUnauthorized());
            postNoBody(a, "/business/confirm").andExpect(status().isUnauthorized());
            postJson(a, "/food-safety/analyze", uids(kit.fs())).andExpect(status().isUnauthorized());
            postNoBody(a, "/food-safety/confirm").andExpect(status().isUnauthorized());
            postNoBody(a, "/cross-validate").andExpect(status().isUnauthorized());
            getPath(a, "/selfie/code").andExpect(status().isUnauthorized());
            postJson(a, "/selfie/analyze", "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}")
                    .andExpect(status().isUnauthorized());
            getPath(a, "/status").andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/verification/status")).andExpect(status().isUnauthorized());
        assertThat(sessionRepository.findByUserId(customer.userId())).isEmpty();
        assertThat(gemini.calls()).isEmpty();
    }

    // ── happy path + certificate seam + scheduled deletion ───────────────────

    @Test
    void fullKyc_pendingReview_createsCertificates_andReviewHookSchedulesDeletion() throws Exception {
        Account chef = register("kyc-chef", UserRole.CHEF);
        Account admin = register("kyc-admin", UserRole.ADMIN);
        Kit kit = newKit();
        assertThat(reviewHook).isInstanceOf(VerificationCertificateReviewHook.class); // seam closed

        // no session yet
        getPath(chef, "/status").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("VERIFICATION_SESSION_NOT_FOUND"));

        // CCCD
        postJson(chef, "/cccd/analyze", uids(kit.front(), kit.back())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_id").isNumber())
                .andExpect(jsonPath("$.data.extracted.full_name").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.data.extracted.cccd_number").value("079123456789"))
                .andExpect(jsonPath("$.data.extracted.address").value("1 Lê Lợi"));
        getPath(chef, "/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.cccd_status").value("EXTRACTED"))
                .andExpect(jsonPath("$.data.business_status").value("PENDING"));
        postNoBody(chef, "/cccd/confirm").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        // confirming twice is refused
        postNoBody(chef, "/cccd/confirm").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DOCUMENT_NOT_READY_TO_CONFIRM"));

        // cross-validate before all three are confirmed
        postNoBody(chef, "/cross-validate").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("CROSS_VALIDATION_NOT_READY"));

        postJson(chef, "/business/analyze", uids(kit.biz1(), kit.biz2())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extracted.business_name").value("Bếp Nhà A"));
        postNoBody(chef, "/business/confirm").andExpect(status().isOk());
        postJson(chef, "/food-safety/analyze", uids(kit.fs())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extracted.expiry_date").value("2099-12-31"));
        postNoBody(chef, "/food-safety/confirm").andExpect(status().isOk());

        postNoBody(chef, "/cross-validate").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.next_step").value("SELFIE"));
        getPath(chef, "/status").andExpect(jsonPath("$.data.status").value("AWAITING_SELFIE"))
                .andExpect(jsonPath("$.data.cross_validation_passed").value(true));

        // selfie
        String code = extract(getPath(chef, "/selfie/code").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verification_code").value(org.hamcrest.Matchers.matchesPattern("VERIFY-\\d{6}")))
                .andReturn().getResponse().getContentAsString(), "verification_code");
        postJson(chef, "/selfie/analyze", "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.risk_score").value(0))
                .andExpect(jsonPath("$.data.risk_flags").isEmpty())
                .andExpect(jsonPath("$.data.face_similarity_score").value(0.92))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(code).startsWith("VERIFY-");

        // status after completion: safe identity, no CCCD number, selfie visible for admin review
        getPath(chef, "/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.cccd_number_masked").value("079******789"))
                .andExpect(jsonPath("$.data.verified_identity.full_name").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.data.verified_identity.date_of_birth").value("1990-01-02"))
                .andExpect(jsonPath("$.data.verified_at").isNotEmpty())
                .andExpect(jsonPath("$.data.selfie_url").value("https://cdn.example.test/selfie.jpg"));

        ChefVerificationSession s = session(chef);
        assertThat(s.getCccdNumberHash()).startsWith("$argon2id$v=19$m=65536,t=2,p=1$");
        assertThat(s.getCccdConfirmed()).isNull();
        assertThat(s.getCccdExtracted()).isNull();
        assertThat(s.getCccdAttachmentUids()).hasSize(2); // PENDING_REVIEW keeps the images for the admin
        assertThat(s.getSelfieAttachmentUid()).isEqualTo(kit.selfie().getUid());
        assertThat(s3.deleted).isEmpty();
        assertThat(attachmentRepository.findById(kit.front().getUid()).orElseThrow().isFileDeleted()).isFalse();

        // certificates: PENDING, one per document, all pages linked in order
        Certificate biz = certificateRepository.findById(s.getBusinessCertificateUid()).orElseThrow();
        Certificate fs = certificateRepository.findById(s.getFoodSafetyCertificateUid()).orElseThrow();
        assertThat(biz.getStatus()).isEqualTo(CertificateStatus.PENDING);
        assertThat(biz.getCertificateType()).isEqualTo(CertificateType.BUSINESS_LICENSE);
        assertThat(biz.getName()).isEqualTo("Bếp Nhà A");
        assertThat(biz.getIssuedBy()).isEqualTo("Cơ quan đăng ký kinh doanh");
        assertThat(biz.getIssueDate()).hasToString("2020-05-06");
        assertThat(fs.getStatus()).isEqualTo(CertificateStatus.PENDING);
        assertThat(fs.getCertificateType()).isEqualTo(CertificateType.FOOD_SAFETY);
        assertThat(fs.getExpirationDate()).hasToString("2099-12-31");
        var bizLinks = certificateAttachmentRepository.findByCertificateOrderByPositionAsc(biz);
        assertThat(bizLinks).extracting(l -> l.getAttachment().getUid()).containsExactly(kit.biz1().getUid(), kit.biz2().getUid());
        assertThat(bizLinks).extracting(l -> l.getPosition()).containsExactly(1, 2);
        assertThat(certificateAttachmentRepository.findByCertificateOrderByPositionAsc(fs)).hasSize(1);

        // the verification flow is finished: it cannot be redone
        postJson(chef, "/selfie/analyze", "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message_code").value("VERIFICATION_ALREADY_COMPLETED"));

        // ---- seam: admin reviews the certificates through the certificate module ----
        mockMvc.perform(patch("/api/certificates/" + biz.getUid() + "/status").header("Authorization", auth(admin))
                .contentType("application/json").content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        // one certificate is still PENDING -> nothing scheduled yet, references kept
        assertThat(scheduledRepository.findAll().stream().map(ScheduledS3Deletion::getAttachmentUid))
                .doesNotContain(kit.front().getUid(), kit.back().getUid(), kit.selfie().getUid());
        assertThat(session(chef).getCccdAttachmentUids()).hasSize(2);

        mockMvc.perform(patch("/api/certificates/" + fs.getUid() + "/status").header("Authorization", auth(admin))
                .contentType("application/json").content("{\"status\":\"REVOKED\",\"rejection_reason\":\"mờ\"}"))
                .andExpect(status().isOk());
        // last certificate reviewed -> CCCD (2) + selfie scheduled 30 days out, references cleared
        List<ScheduledS3Deletion> scheduled = scheduledRepository.findAll().stream()
                .filter(r -> List.of(kit.front().getUid(), kit.back().getUid(), kit.selfie().getUid()).contains(r.getAttachmentUid()))
                .toList();
        assertThat(scheduled).hasSize(3);
        assertThat(scheduled).allSatisfy(r -> {
            assertThat(r.isExecuted()).isFalse();
            assertThat(r.getS3Bucket()).isEqualTo("kyc-bucket");
            assertThat(Duration.between(Instant.now(), r.getDeleteAfter()))
                    .isBetween(Duration.ofDays(30).minusMinutes(5), Duration.ofDays(30).plusMinutes(5));
        });
        s = session(chef);
        assertThat(s.getCccdAttachmentUids()).isEmpty();
        assertThat(s.getSelfieAttachmentUid()).isNull();
        getPath(chef, "/status").andExpect(jsonPath("$.data.selfie_url").isEmpty());

        // reviewing again is idempotent (get_or_create semantics: no duplicate rows)
        mockMvc.perform(patch("/api/certificates/" + fs.getUid() + "/status").header("Authorization", auth(admin))
                .contentType("application/json").content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        assertThat(scheduledRepository.findByAttachmentUid(kit.selfie().getUid())).hasSize(1);

        // not due yet -> the daily job deletes nothing of ours
        s3DeletionService.executePending(false);
        assertThat(s3.deleted).isEmpty();
        // make them due; the daily job now deletes the objects and flags the attachments
        scheduled.forEach(r -> {
            ScheduledS3Deletion row = scheduledRepository.findById(r.getId()).orElseThrow();
            row.setDeleteAfter(Instant.now().minusSeconds(60));
            scheduledRepository.save(row);
        });
        s3DeletionService.executePending(false);
        assertThat(s3.deleted).hasSize(3).allMatch(k -> k.startsWith("kyc-bucket/certificate/"));
        assertThat(attachmentRepository.findById(kit.selfie().getUid()).orElseThrow().isFileDeleted()).isTrue();
        assertThat(attachmentRepository.findById(kit.front().getUid()).orElseThrow().isDeleted()).isTrue();
        assertThat(scheduledRepository.findByAttachmentUid(kit.front().getUid())).allMatch(ScheduledS3Deletion::isExecuted);
    }

    // ── rejected path ────────────────────────────────────────────────────────

    @Test
    void lowFaceMatch_isRejected_deletesImagesImmediately_createsNoCertificates() throws Exception {
        Account chef = register("kyc-reject", UserRole.CHEF);
        Kit kit = newKit();
        bizSignature = "false"; // +10
        faceMatcher.score = Optional.of(0.30); // +70 => 80 => REJECTED
        confirmAllDocuments(chef, kit);
        passCrossValidationAndSubmitSelfie(chef, kit);

        postJson(chef, "/selfie/analyze", "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.risk_score").value(80))
                .andExpect(jsonPath("$.data.risk_flags[0]").value("MISSING_SIGNATURE_BUSINESS"))
                .andExpect(jsonPath("$.data.risk_flags[1]").value("FACE_MATCH_FAILED"))
                .andExpect(jsonPath("$.data.face_similarity_score").value(0.3));

        ChefVerificationSession s = session(chef);
        assertThat(s.getBusinessCertificateUid()).isNull();
        assertThat(s.getFoodSafetyCertificateUid()).isNull();
        assertThat(s.getCccdAttachmentUids()).isEmpty();
        assertThat(s.getSelfieAttachmentUid()).isNull();
        assertThat(s.getCccdNumberMasked()).isEqualTo("079******789");
        assertThat(s3.deleted).hasSize(3); // front, back, selfie
        assertThat(attachmentRepository.findById(kit.front().getUid()).orElseThrow().isFileDeleted()).isTrue();
        assertThat(attachmentRepository.findById(kit.selfie().getUid()).orElseThrow().isDeleted()).isTrue();
        getPath(chef, "/status").andExpect(jsonPath("$.data.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.selfie_url").isEmpty());
    }

    @Test
    void noFaceEngine_meansFaceNotDetected_plus50_stillPendingReview() throws Exception {
        Account chef = register("kyc-noface", UserRole.CHEF);
        Kit kit = newKit();
        faceMatcher.score = Optional.empty();
        confirmAllDocuments(chef, kit);
        passCrossValidationAndSubmitSelfie(chef, kit);
        postJson(chef, "/selfie/analyze", "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.risk_score").value(50))
                .andExpect(jsonPath("$.data.risk_flags[0]").value("FACE_NOT_DETECTED"))
                .andExpect(jsonPath("$.data.face_similarity_score").isEmpty());
    }

    // ── error paths ──────────────────────────────────────────────────────────

    @Test
    void requestValidation_andAttachmentErrors() throws Exception {
        Account chef = register("kyc-errs", UserRole.CHEF);
        Kit kit = newKit();
        // CCCD needs exactly two images; documents need 1..10
        postJson(chef, "/cccd/analyze", uids(kit.front())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        postJson(chef, "/business/analyze", "{\"attachment_uids\":[]}").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        Attachment[] eleven = new Attachment[11];
        java.util.Arrays.fill(eleven, kit.biz1());
        postJson(chef, "/food-safety/analyze", uids(eleven)).andExpect(status().isUnauthorized());
        postJson(chef, "/selfie/analyze", "{}").andExpect(status().isUnauthorized());

        // unknown / incomplete attachments
        postJson(chef, "/cccd/analyze", "{\"attachment_uids\":[\"" + UUID.randomUUID() + "\",\"" + kit.back().getUid() + "\"]}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("ATTACHMENT_NOT_FOUND"));
        Attachment pending = attachment("pending.jpg", false);
        postJson(chef, "/business/analyze", uids(pending)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("ATTACHMENT_IS_NOT_COMPLETED"));
        assertThat(gemini.calls()).isEmpty();
        // Django get_or_create ran first and is not rolled back by the later failure
        getPath(chef, "/status").andExpect(status().isOk()).andExpect(jsonPath("$.data.cccd_status").value("PENDING"));
    }

    @Test
    void documentAnalysisErrors_are422WithDetail_andConfirmStaysBlocked() throws Exception {
        Account chef = register("kyc-doc", UserRole.CHEF);
        Kit kit = newKit();
        gemini.respondWith(p -> "{\"document_errors\": [\"IMAGE_BLURRY\"]}");

        postJson(chef, "/business/analyze", uids(kit.biz1())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message_code").value("DOCUMENT_ANALYSIS_ERROR"))
                .andExpect(jsonPath("$.data[0].code").value("IMAGE_BLURRY"))
                .andExpect(jsonPath("$.data[0].message").value("Ảnh giấy phép kinh doanh bị mờ."))
                .andExpect(jsonPath("$.data[1].code").value("OWNER_NAME_NOT_READABLE"))
                .andExpect(jsonPath("$.data[2].code").value("LICENSE_NUMBER_NOT_READABLE"));
        postNoBody(chef, "/business/confirm").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("DOCUMENT_NOT_READY_TO_CONFIRM"));
        getPath(chef, "/status").andExpect(jsonPath("$.data.business_status").value("PENDING"));
    }

    @Test
    void geminiFailure_isServerError_likeDjango() throws Exception {
        Account chef = register("kyc-boom", UserRole.CHEF);
        Kit kit = newKit();
        gemini.respondWith(p -> {
            throw new IllegalStateException("gemini down");
        });
        postJson(chef, "/food-safety/analyze", uids(kit.fs())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
        gemini.respondWith(p -> "not json");
        postJson(chef, "/food-safety/analyze", uids(kit.fs())).andExpect(status().isInternalServerError());
    }

    @Test
    void crossValidationFailure_isPersisted_422_andFlagsFeedRiskScore() throws Exception {
        Account chef = register("kyc-cross", UserRole.CHEF);
        Kit kit = newKit();
        bizOwner = "Tran Thi B";              // != CCCD name
        fsOwner = "Le Van C";                 // != business owner
        fsExpiry = "2001-01-01";              // expired
        addressVerdict = "{\"same_location\": false, \"confidence\": \"high\"}";
        confirmAllDocuments(chef, kit);

        postNoBody(chef, "/cross-validate").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message_code").value("CROSS_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[0].code").value("OWNER_NAME_MISMATCH_CCCD_BUSINESS"))
                .andExpect(jsonPath("$.data[0].documents[0]").value("cccd"))
                .andExpect(jsonPath("$.data[0].documents[1]").value("business"))
                .andExpect(jsonPath("$.data[1].code").value("OWNER_NAME_MISMATCH_BUSINESS_FOOD_SAFETY"))
                .andExpect(jsonPath("$.data[2].code").value("ADDRESS_MISMATCH_BUSINESS_FOOD_SAFETY"))
                .andExpect(jsonPath("$.data[3].code").value("FOOD_SAFETY_CERT_EXPIRED"));
        // persisted although the request failed (persist-then-raise, autocommit semantics)
        getPath(chef, "/status").andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.cross_validation_passed").value(false));
        assertThat(session(chef).getCrossValidationErrors()).hasSize(4);

        // the chef may still push on to the selfie (Django does not block it): 50 + 30 + 100 => rejected
        getPath(chef, "/selfie/code").andExpect(status().isOk());
        postJson(chef, "/selfie/analyze", "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.risk_score").value(180))
                .andExpect(jsonPath("$.data.risk_flags[0]").value("OWNER_NAME_MISMATCH"))
                .andExpect(jsonPath("$.data.risk_flags[1]").value("ADDRESS_MISMATCH"))
                .andExpect(jsonPath("$.data.risk_flags[2]").value("FOOD_SAFETY_CERT_EXPIRED"));
    }

    @Test
    void selfie_codeMissingExpiredWrongAndUnreadable() throws Exception {
        Account chef = register("kyc-selfie", UserRole.CHEF);
        Kit kit = newKit();
        String body = "{\"attachment_uid\":\"" + kit.selfie().getUid() + "\"}";

        // no code ever generated
        postJson(chef, "/selfie/analyze", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("SELFIE_CODE_EXPIRED"));

        getPath(chef, "/selfie/code").andExpect(status().isOk());
        // expired
        ChefVerificationSession s = session(chef);
        s.setVerificationCodeExpiresAt(Instant.now().minusSeconds(1));
        sessionRepository.save(s);
        postJson(chef, "/selfie/analyze", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("SELFIE_CODE_EXPIRED"));

        // fresh code, but Gemini reads another one
        getPath(chef, "/selfie/code").andExpect(status().isOk());
        gemini.respondWith(p -> """
                {"face_detected": true, "has_id_card": true, "has_verification_code": true,
                 "verification_code_read": "VERIFY-000000", "document_errors": []}""");
        postJson(chef, "/selfie/analyze", body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message_code").value("SELFIE_CODE_MISMATCH"));

        // Gemini reports selfie problems
        gemini.respondWith(p -> "{\"document_errors\": [\"VERIFICATION_CODE_NOT_VISIBLE\"], \"face_detected\": true}");
        postJson(chef, "/selfie/analyze", body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message_code").value("DOCUMENT_ANALYSIS_ERROR"))
                .andExpect(jsonPath("$.data[0].code").value("VERIFICATION_CODE_NOT_VISIBLE"))
                .andExpect(jsonPath("$.data[0].message").value("Không thấy tờ giấy có mã xác thực trong ảnh."));

        // none of these completed the session
        getPath(chef, "/status").andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.decision").isEmpty());
        // a second code request replaces the first
        String first = session(chef).getVerificationCode();
        getPath(chef, "/selfie/code").andExpect(status().isOk());
        assertThat(session(chef).getVerificationCode()).matches("VERIFY-\\d{6}");
        assertThat(first).matches("VERIFY-\\d{6}");
    }

    @Test
    void qrScannerNotAvailable_doesNotBlockCccdAnalysis() throws Exception {
        Account chef = register("kyc-qr", UserRole.CHEF);
        Kit kit = newKit();
        fetcher.failFromCall(3); // Gemini downloads front+back (calls 1-2); the QR step's back download (call 3) fails -> swallowed
        postJson(chef, "/cccd/analyze", uids(kit.front(), kit.back())).andExpect(status().isOk());
        assertThat(session(chef).getCccdExtracted()).containsEntry("qr_verified", false);
    }
}
