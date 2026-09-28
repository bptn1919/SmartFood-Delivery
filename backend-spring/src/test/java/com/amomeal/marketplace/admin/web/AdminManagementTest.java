package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.certificate.dto.CertificateRequest;
import com.amomeal.marketplace.certificate.entity.CertificateAttachment;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.repository.CertificateAttachmentRepository;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.certificate.service.CertificateService;
import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import com.amomeal.marketplace.payment.repository.CustomerPaymentInfoRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * User management, platform-voucher creation, certificate review, KYC review data and bank-account review:
 * everything in Django's admin app that mutates or reads another module's data.
 */
class AdminManagementTest extends AbstractAdminFullStackTest {

    @Autowired private VoucherRepository voucherRepository;
    @Autowired private AppliedVoucherRepository appliedVoucherRepository;
    @Autowired private CertificateService certificateService;
    @Autowired private CertificateRepository certificateRepository;
    @Autowired private CertificateAttachmentRepository certificateAttachmentRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private ChefVerificationSessionRepository sessionRepository;
    @Autowired private CustomerPaymentInfoRepository customerPaymentInfoRepository;

    // =================================================================== users

    private Account tagged(String tag, String suffix, UserRole role) throws Exception {
        return register(tag + suffix, role);
    }

    @Test
    void users_list_showsOnlyCustomersAndChefs_withFiltersPaginationAndShape() throws Exception {
        Account admin = register("usr-admin", UserRole.ADMIN);
        String tag = "ul" + System.nanoTime();
        Account cust = tagged(tag, "c", UserRole.CUSTOMER);
        Account chef = tagged(tag, "k", UserRole.CHEF);
        Account inactive = tagged(tag, "i", UserRole.CUSTOMER);
        Account both = tagged(tag, "b", UserRole.CUSTOMER);
        tagged(tag, "a", UserRole.ADMIN);                                       // admin-only: never listed
        setName(cust, "Lan", "Tran");
        CustomUser i = userRepository.findById(inactive.userId()).orElseThrow();
        i.setActive(false);
        userRepository.save(i);
        CustomUser b = userRepository.findById(both.userId()).orElseThrow();
        b.addRole(UserRole.ADMIN);
        userRepository.save(b);

        JsonNode all = body(getAs(admin, "/api/admin/users?search=" + tag).andExpect(status().isOk())).get("data");
        assertThat(all.get("total_rows").asInt()).isEqualTo(4);
        // newest joined first: both, inactive, chef, cust
        assertThat(all.get("content").get(0).get("id").asLong()).isEqualTo(both.userId());
        assertThat(all.get("content").get(3).get("id").asLong()).isEqualTo(cust.userId());
        JsonNode custItem = all.get("content").get(3);
        assertThat(custItem.get("username").asString()).isEqualTo(cust.username());
        assertThat(custItem.get("email").asString()).isEqualTo(cust.email());
        assertThat(custItem.get("first_name").asString()).isEqualTo("Lan");
        assertThat(custItem.get("last_name").asString()).isEqualTo("Tran");
        assertThat(custItem.get("phone_number").asString()).isEqualTo("0900000099");
        assertThat(custItem.get("is_active").asBoolean()).isTrue();
        assertThat(custItem.get("is_staff").asBoolean()).isFalse();
        assertThat(custItem.get("groups")).extracting(JsonNode::asString).containsExactly("CUSTOMER");
        assertThat(custItem.get("date_joined").asString()).endsWith("+00:00");
        assertThat(all.get("content").get(0).get("groups")).extracting(JsonNode::asString)
                .containsExactly("CUSTOMER", "ADMIN");                          // every group is reported
        assertThat(all.get("content").get(2).get("groups")).extracting(JsonNode::asString).containsExactly("CHEF");

        // search is a case-insensitive username/email substring
        assertThat(body(getAs(admin, "/api/admin/users?search=" + tag.toUpperCase()))
                .get("data").get("total_rows").asInt()).isEqualTo(4);

        // role filter; an unknown / admin user_type is ignored (Django returns Q())
        assertThat(rows(admin, tag, "&user_type=chef")).isEqualTo(1);
        assertThat(rows(admin, tag, "&user_type=CUSTOMER")).isEqualTo(3);
        assertThat(rows(admin, tag, "&user_type=admin")).isEqualTo(4);
        assertThat(rows(admin, tag, "&user_type=nonsense")).isEqualTo(4);

        // active filter
        assertThat(rows(admin, tag, "&is_active=false")).isEqualTo(1);
        assertThat(rows(admin, tag, "&is_active=true")).isEqualTo(3);
        getAs(admin, "/api/admin/users?is_active=maybe").andExpect(status().isUnauthorized());   // validation error

        // joined-date window (UTC days); a malformed date is ignored
        assertThat(rows(admin, tag, "&from_date=2999-01-01")).isZero();
        assertThat(rows(admin, tag, "&to_date=2000-01-01")).isZero();
        assertThat(rows(admin, tag, "&from_date=2000-01-01&to_date=2999-01-01")).isEqualTo(4);
        assertThat(rows(admin, tag, "&from_date=garbage")).isEqualTo(4);

        // pagination
        JsonNode p2 = body(getAs(admin, "/api/admin/users?search=" + tag + "&page_size=3&page=2")).get("data");
        assertThat(p2.get("total_pages").asInt()).isEqualTo(2);
        assertThat(p2.get("content")).hasSize(1);
        assertThat(p2.get("current_page").asInt()).isEqualTo(2);
    }

    private int rows(Account admin, String tag, String extra) throws Exception {
        return body(getAs(admin, "/api/admin/users?search=" + tag + extra).andExpect(status().isOk()))
                .get("data").get("total_rows").asInt();
    }

    @Test
    void users_deactivateActivate_flipFlag_returnBool_andUnknownIdIsFalseNot404() throws Exception {
        Account admin = register("act-admin", UserRole.ADMIN);
        Account cust = register("act-cust", UserRole.CUSTOMER);

        JsonNode off = body(patchAs(admin, "/api/admin/users/" + cust.userId() + "/deactivate").andExpect(status().isOk()));
        assertThat(off.get("data").asBoolean()).isTrue();
        assertThat(userRepository.findById(cust.userId()).orElseThrow().isActive()).isFalse();
        // a deactivated account can no longer log in (users module's is_active check)
        int loginStatus = postJson(null, "/api/auth/login",
                "{\"email\":\"" + cust.email() + "\",\"password\":\"correct-horse-battery\"}").andReturn().getResponse().getStatus();
        assertThat(loginStatus).isGreaterThanOrEqualTo(400);

        JsonNode on = body(patchAs(admin, "/api/admin/users/" + cust.userId() + "/activate").andExpect(status().isOk()));
        assertThat(on.get("data").asBoolean()).isTrue();
        assertThat(userRepository.findById(cust.userId()).orElseThrow().isActive()).isTrue();
        postJson(null, "/api/auth/login", "{\"email\":\"" + cust.email() + "\",\"password\":\"correct-horse-battery\"}")
                .andExpect(status().isOk());

        // Django returns False (HTTP 200), not a 404, for an id that does not exist
        assertThat(body(patchAs(admin, "/api/admin/users/987654321/deactivate").andExpect(status().isOk()))
                .get("data").asBoolean()).isFalse();
        patchAs(admin, "/api/admin/users/abc/deactivate").andExpect(status().isUnauthorized());   // validation
    }

    // =================================================================== vouchers

    private static String voucherJson(String code, String type, String discountType, String value, String start, String end,
                                      String extra) {
        return """
                {"code":"%s","name":"Platform promo","voucher_type":"%s","discount_type":"%s","discount_value":%s,
                 "start_date":"%s","end_date":"%s"%s}
                """.formatted(code, type, discountType, value, start, end, extra);
    }

    @Test
    void voucher_create_acceptsFeAdminNaiveDatetimes_uppercasesCode_setsAdminAsOwner_andListsNewestFirst() throws Exception {
        Account admin = register("vch-admin", UserRole.ADMIN);
        String code = "vch" + System.nanoTime();
        JsonNode created = body(postJson(admin, "/api/admin/voucher", voucherJson(code, "PLATFORM_SUBTOTAL", "PERCENTAGE",
                "10", "2026-01-01T10:00", "2026-12-31T23:30",
                ",\"description\":\"d\",\"max_discount_amount\":50000,\"min_order_amount\":100000,\"usage_limit\":100,\"usage_limit_per_user\":2"))
                .andExpect(status().isOk())).get("data");
        assertThat(created.get("code").asString()).isEqualTo(code.toUpperCase());
        assertThat(created.get("name").asString()).isEqualTo("Platform promo");
        assertThat(created.get("description").asString()).isEqualTo("d");
        assertThat(created.get("voucher_type").asString()).isEqualTo("PLATFORM_SUBTOTAL");
        assertThat(created.get("discount_type").asString()).isEqualTo("PERCENTAGE");   // additive vs Django (FE-admin reads it)
        assertThat(created.get("discount_value").asDouble()).isEqualTo(10.0);
        assertThat(created.get("max_discount_amount").asDouble()).isEqualTo(50000.0);
        assertThat(created.get("min_order_amount").asDouble()).isEqualTo(100000.0);
        assertThat(created.get("usage_limit").asInt()).isEqualTo(100);
        assertThat(created.get("usage_limit_per_user").asInt()).isEqualTo(2);
        assertThat(created.get("usage_count").asInt()).isZero();
        assertThat(created.get("is_active").asBoolean()).isTrue();
        assertThat(created.get("start_date").asString()).startsWith("2026-01-01T10:00");       // naive -> UTC
        assertThat(created.get("end_date").asString()).startsWith("2026-12-31T23:30");
        UUID uid = UUID.fromString(created.get("uid").asString());
        assertThat(voucherRepository.findById(uid).orElseThrow().getChef().getId()).isEqualTo(admin.userId());

        // usage_count = RESERVED + USED applied vouchers only
        Account user = register("vch-user", UserRole.CUSTOMER);
        var v = voucherRepository.findById(uid).orElseThrow();
        var u = userRepository.getReferenceById(user.userId());
        appliedVoucherRepository.save(AppliedVoucher.builder().voucher(v).user(u).orderUid(UUID.randomUUID())
                .voucherType(v.getVoucherType()).discountAmount(BigDecimal.ONE).status(VoucherReservationStatus.RESERVED).build());
        appliedVoucherRepository.save(AppliedVoucher.builder().voucher(v).user(u).orderUid(UUID.randomUUID())
                .voucherType(v.getVoucherType()).discountAmount(BigDecimal.ONE).status(VoucherReservationStatus.CANCELLED).build());

        JsonNode list = body(getAs(admin, "/api/admin/voucher").andExpect(status().isOk())).get("data");
        assertThat(list.isArray()).isTrue();                                                   // plain array, no pagination
        assertThat(list.get(0).get("uid").asString()).isEqualTo(uid.toString());              // newest first
        assertThat(list.get(0).get("usage_count").asInt()).isEqualTo(1);

        // ISO with zone, a bare date, and zero max_discount_amount (Django stores None) all work
        JsonNode second = body(postJson(admin, "/api/admin/voucher", voucherJson(code + "2", "PLATFORM_SHIPPING",
                "FIXED_AMOUNT", "0", "2026-02-01T00:00:00Z", "2026-03-01", ",\"max_discount_amount\":0"))
                .andExpect(status().isOk())).get("data");
        assertThat(second.get("voucher_type").asString()).isEqualTo("PLATFORM_SHIPPING");
        assertThat(second.get("max_discount_amount").isNull()).isTrue();
        assertThat(second.get("discount_value").asDouble()).isZero();                          // FIXED_AMOUNT 0 is not checked
        assertThat(second.get("end_date").asString()).startsWith("2026-03-01T00:00");
    }

    @Test
    void voucher_create_businessRules_inDjangosOrder() throws Exception {
        Account admin = register("vch2-admin", UserRole.ADMIN);
        String code = "rules" + System.nanoTime();
        postJson(admin, "/api/admin/voucher", voucherJson(code, "PLATFORM_SUBTOTAL", "PERCENTAGE", "10",
                "2026-01-01T00:00", "2026-12-31T00:00", "")).andExpect(status().isOk());

        // 1. duplicate code (case-insensitive) beats every later rule
        JsonNode dup = body(postJson(admin, "/api/admin/voucher", voucherJson(code.toUpperCase(), "SHOP_VOUCHER", "PERCENTAGE", "500",
                "2027-01-01T00:00", "2026-01-01T00:00", "")).andExpect(status().isBadRequest()));
        assertThat(dup.get("message_code").asString()).isEqualTo("VOUCHER_CODE_ALREADY_EXISTS");

        // 2. start must be strictly before end
        String c2 = code + "b";
        assertThat(body(postJson(admin, "/api/admin/voucher", voucherJson(c2, "SHOP_VOUCHER", "PERCENTAGE", "500",
                "2026-05-01T00:00", "2026-05-01T00:00", "")).andExpect(status().isBadRequest())).get("data").asString())
                .isEqualTo("Ngày bắt đầu phải trước ngày kết thúc");

        // 3. percentage in (0, 100]
        for (String bad : List.of("0", "-5", "100.01", "150")) {
            JsonNode r = body(postJson(admin, "/api/admin/voucher", voucherJson(c2, "SHOP_VOUCHER", "PERCENTAGE", bad,
                    "2026-01-01T00:00", "2026-12-31T00:00", "")).andExpect(status().isBadRequest()));
            assertThat(r.get("message_code").asString()).isEqualTo("VOUCHER_INVALID");
            assertThat(r.get("data").asString()).isEqualTo("Giá trị giảm giá phần trăm phải trong khoảng (0, 100]");
        }
        postJson(admin, "/api/admin/voucher", voucherJson(code + "ok100", "PLATFORM_SUBTOTAL", "PERCENTAGE", "100",
                "2026-01-01T00:00", "2026-12-31T00:00", "")).andExpect(status().isOk());

        // 4. admin may only create the two PLATFORM_* types (SHOP_VOUCHER passes the schema, fails the service)
        JsonNode shop = body(postJson(admin, "/api/admin/voucher", voucherJson(c2, "SHOP_VOUCHER", "PERCENTAGE", "10",
                "2026-01-01T00:00", "2026-12-31T00:00", "")).andExpect(status().isBadRequest()));
        assertThat(shop.get("message_code").asString()).isEqualTo("VOUCHER_INVALID");
        assertThat(shop.get("data").asString()).isEqualTo("Admin chỉ được tạo voucher loại PLATFORM_SUBTOTAL hoặc PLATFORM_SHIPPING");
        assertThat(voucherRepository.existsByCodeIgnoreCase(c2)).isFalse();                   // nothing persisted

        // schema-level failures are 401 VALIDATION_ERROR
        postJson(admin, "/api/admin/voucher", voucherJson(c2, "NOT_A_TYPE", "PERCENTAGE", "10", "2026-01-01T00:00",
                "2026-12-31T00:00", "")).andExpect(status().isUnauthorized());
        JsonNode badDate = body(postJson(admin, "/api/admin/voucher", voucherJson(c2, "PLATFORM_SUBTOTAL", "PERCENTAGE", "10",
                "next tuesday", "2026-12-31T00:00", "")).andExpect(status().isUnauthorized()));
        assertThat(badDate.get("message_code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(badDate.get("data").has("start_date")).isTrue();
    }

    // =================================================================== certificate review

    private UUID pendingCertificate(Account owner, CertificateType type) {
        CustomUser u = userRepository.findById(owner.userId()).orElseThrow();
        return certificateService.createCertificate(u, new CertificateRequest("Giay chung nhan", "mo ta", "So Y te",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), type)).uid();
    }

    @Test
    void certificate_setStatus_updatesReviewerAndTime_anyTargetStatus_and404ForUnknownOrDeleted() throws Exception {
        Account admin = register("cer-admin", UserRole.ADMIN);
        Account chef = register("cer-chef", UserRole.CHEF);
        UUID uid = pendingCertificate(chef, CertificateType.FOOD_SAFETY);

        assertThat(body(patchAs(admin, "/api/admin/certificate/" + uid + "?status=ACTIVE").andExpect(status().isOk()))
                .get("data").asBoolean()).isTrue();
        JsonNode cert = body(getAs(admin, "/api/certificates/" + uid).andExpect(status().isOk())).get("data");
        assertThat(cert.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(cert.get("verified_by").asLong()).isEqualTo(admin.userId());
        assertThat(cert.get("verified_at").isNull()).isFalse();

        // no transition rules and no rejection-reason rule on this route
        patchAs(admin, "/api/admin/certificate/" + uid + "?status=REVOKED").andExpect(status().isOk());
        assertThat(certificateRepository.findById(uid).orElseThrow().getStatus().name()).isEqualTo("REVOKED");
        patchAs(admin, "/api/admin/certificate/" + uid + "?status=PENDING").andExpect(status().isOk());
        assertThat(certificateRepository.findById(uid).orElseThrow().getStatus().name()).isEqualTo("PENDING");

        // unknown uid: 404 HTTP_ERROR "Certificate not found" (ninja HttpError)
        JsonNode nf = body(patchAs(admin, "/api/admin/certificate/" + UUID.randomUUID() + "?status=ACTIVE")
                .andExpect(status().isNotFound()));
        assertThat(nf.get("message_code").asString()).isEqualTo("HTTP_ERROR");
        assertThat(nf.get("data").asString()).isEqualTo("Certificate not found");
        // a soft-deleted certificate is also "not found"
        certificateService.softDelete(userRepository.findById(admin.userId()).orElseThrow(), uid);
        patchAs(admin, "/api/admin/certificate/" + uid + "?status=ACTIVE").andExpect(status().isNotFound());
        patchAs(admin, "/api/admin/certificate/not-a-uuid?status=ACTIVE").andExpect(status().isInternalServerError());
    }

    @Test
    void certificate_lastReview_runsTheKycCleanupHook_scrubbingCccdAndSelfieRefs() throws Exception {
        Account admin = register("kyc-admin", UserRole.ADMIN);
        Account chef = register("kyc-chef", UserRole.CHEF);
        UUID business = pendingCertificate(chef, CertificateType.BUSINESS_LICENSE);
        UUID food = pendingCertificate(chef, CertificateType.FOOD_SAFETY);
        Attachment cccd = attachment("cccd-front");
        Attachment selfie = attachment("selfie");
        ChefVerificationSession s = new ChefVerificationSession();
        s.setUserId(chef.userId());
        s.setBusinessCertificateUid(business);
        s.setFoodSafetyCertificateUid(food);
        s.setCccdAttachmentUids(new ArrayList<>(List.of(cccd.getUid().toString())));
        s.setSelfieAttachmentUid(selfie.getUid());
        sessionRepository.save(s);

        // first approval: the other certificate is still PENDING, so nothing is scrubbed yet
        patchAs(admin, "/api/admin/certificate/" + business + "?status=ACTIVE").andExpect(status().isOk());
        ChefVerificationSession mid = sessionRepository.findByUserId(chef.userId()).orElseThrow();
        assertThat(mid.getCccdAttachmentUids()).hasSize(1);
        assertThat(mid.getSelfieAttachmentUid()).isEqualTo(selfie.getUid());

        // last approval: no PENDING left -> the seam (verification's hook) clears the sensitive references
        patchAs(admin, "/api/admin/certificate/" + food + "?status=REVOKED").andExpect(status().isOk());
        ChefVerificationSession done = sessionRepository.findByUserId(chef.userId()).orElseThrow();
        assertThat(done.getCccdAttachmentUids()).isEmpty();
        assertThat(done.getSelfieAttachmentUid()).isNull();
    }

    private Attachment attachment(String name) {
        return attachmentRepository.save(Attachment.builder().type(AttachmentType.OTHER).originalName(name + ".jpg")
                .hashedName(name + "-" + UUID.randomUUID() + ".jpg").size(10).contentType("image/jpeg").bucket("b")
                .directory("kyc").publicUrl("https://cdn.example.test/" + name + ".jpg").isCompleted(true).build());
    }

    // =================================================================== verification review

    @Test
    void verificationReview_returnsSessionCertificatesAndOnlyLiveAttachmentUrls() throws Exception {
        Account admin = register("vr-admin", UserRole.ADMIN);
        Account chef = register("vr-chef", UserRole.CHEF);
        UUID business = pendingCertificate(chef, CertificateType.BUSINESS_LICENSE);
        UUID food = pendingCertificate(chef, CertificateType.FOOD_SAFETY);
        patchAs(admin, "/api/admin/certificate/" + food + "?status=ACTIVE").andExpect(status().isOk());   // verified_by/at on food

        Attachment cccd1 = attachment("cccd1");
        Attachment cccdFileDeleted = attachmentRepository.save(Attachment.builder().type(AttachmentType.OTHER)
                .originalName("x.jpg").hashedName("x-" + UUID.randomUUID()).size(1).contentType("image/jpeg").bucket("b")
                .directory("kyc").publicUrl("https://cdn.example.test/gone.jpg").isFileDeleted(true).build());
        Attachment selfie = attachment("selfie");
        Attachment page1 = attachment("biz1");
        Attachment page2 = attachment("biz2");
        Attachment page3Deleted = attachmentRepository.save(Attachment.builder().type(AttachmentType.OTHER)
                .originalName("y.jpg").hashedName("y-" + UUID.randomUUID()).size(1).contentType("image/jpeg").bucket("b")
                .directory("kyc").publicUrl("https://cdn.example.test/gone2.jpg").isFileDeleted(true).build());
        var bizCert = certificateRepository.findById(business).orElseThrow();
        certificateAttachmentRepository.save(CertificateAttachment.builder().certificate(bizCert).attachment(page2).position(2).build());
        certificateAttachmentRepository.save(CertificateAttachment.builder().certificate(bizCert).attachment(page1).position(1).build());
        certificateAttachmentRepository.save(CertificateAttachment.builder().certificate(bizCert).attachment(page3Deleted).position(3).build());

        ChefVerificationSession s = new ChefVerificationSession();
        s.setUserId(chef.userId());
        s.setBusinessCertificateUid(business);
        s.setFoodSafetyCertificateUid(food);
        s.setCccdAttachmentUids(new ArrayList<>(List.of(cccd1.getUid().toString(), cccdFileDeleted.getUid().toString(), "not-a-uuid")));
        s.setSelfieAttachmentUid(selfie.getUid());
        s.setDecision("PENDING_REVIEW");
        s.setRiskScore(70);
        s.setRiskFlags(new ArrayList<>(List.of("FACE_NOT_DETECTED")));
        s.setVerifiedIdentity(Map.of("full_name", "NGUYEN VAN A", "date_of_birth", "1990-01-01"));
        s.setCccdNumberMasked("079*******89");
        sessionRepository.save(s);

        JsonNode d = body(getAs(admin, "/api/admin/verification/" + chef.userId()).andExpect(status().isOk())).get("data");
        assertThat(d.get("user_id").asLong()).isEqualTo(chef.userId());
        assertThat(d.get("user_email").asString()).isEqualTo(chef.email());
        assertThat(d.get("decision").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(d.get("risk_score").asInt()).isEqualTo(70);
        assertThat(d.get("risk_flags")).extracting(JsonNode::asString).containsExactly("FACE_NOT_DETECTED");
        assertThat(d.get("face_similarity_score").isNull()).isTrue();
        assertThat(d.get("verified_identity").get("full_name").asString()).isEqualTo("NGUYEN VAN A");
        assertThat(d.get("cccd_number_masked").asString()).isEqualTo("079*******89");
        assertThat(d.get("selfie_url").asString()).isEqualTo("https://cdn.example.test/selfie.jpg");
        assertThat(d.get("cccd_image_urls")).extracting(JsonNode::asString).containsExactly("https://cdn.example.test/cccd1.jpg");
        assertThat(d.get("verified_at").isNull()).isTrue();

        JsonNode certs = d.get("certificates");
        assertThat(certs).hasSize(2);
        JsonNode biz = certs.get(0);
        assertThat(biz.get("uid").asString()).isEqualTo(business.toString());
        assertThat(biz.get("certificate_type").asString()).isEqualTo("BUSINESS_LICENSE");
        assertThat(biz.get("status").asString()).isEqualTo("PENDING");
        assertThat(biz.get("issued_by").asString()).isEqualTo("So Y te");
        assertThat(biz.get("issue_date").asString()).isEqualTo("2026-01-01");
        assertThat(biz.get("expiry_date").asString()).isEqualTo("2027-01-01");
        assertThat(biz.get("verified_by_email").isNull()).isTrue();
        assertThat(biz.get("attachments")).hasSize(2);                                       // file-deleted page skipped
        assertThat(biz.get("attachments").get(0).get("position").asInt()).isEqualTo(1);      // ordered by position
        assertThat(biz.get("attachments").get(0).get("url").asString()).isEqualTo("https://cdn.example.test/biz1.jpg");
        assertThat(biz.get("attachments").get(1).get("position").asInt()).isEqualTo(2);
        JsonNode fs = certs.get(1);
        assertThat(fs.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(fs.get("verified_by_email").asString()).isEqualTo(admin.email());
        assertThat(fs.get("verified_at").asString()).endsWith("+00:00");

        // after the review scrubs the refs, the images disappear from the review (as in Django)
        s = sessionRepository.findByUserId(chef.userId()).orElseThrow();
        s.getCccdAttachmentUids().clear();
        s.setSelfieAttachmentUid(null);
        sessionRepository.save(s);
        JsonNode scrubbed = body(getAs(admin, "/api/admin/verification/" + chef.userId())).get("data");
        assertThat(scrubbed.get("cccd_image_urls")).isEmpty();
        assertThat(scrubbed.get("selfie_url").isNull()).isTrue();
    }

    @Test
    void verificationReview_noSession_is403PermissionDeniedWithDjangosMessage() throws Exception {
        Account admin = register("vr2-admin", UserRole.ADMIN);
        JsonNode r = body(getAs(admin, "/api/admin/verification/987654321").andExpect(status().isForbidden()));
        assertThat(r.get("message_code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(r.get("message").asString()).isEqualTo("Không tìm thấy phiên xác minh cho user này.");
    }

    // =================================================================== bank accounts

    @Test
    void chefBankAccounts_list_excludesDeleted_filtersStatusAndSearch_newestFirst() throws Exception {
        Account admin = register("bk-admin", UserRole.ADMIN);
        String tag = "bkc" + System.nanoTime();
        Account verified = tagged(tag, "v", UserRole.CHEF);
        Account unverified = tagged(tag, "u", UserRole.CHEF);
        Account deleted = tagged(tag, "d", UserRole.CHEF);
        setName(verified, "Hoa", "Pham");
        long vId = insertChefBank(verified, true, false);
        long uId = insertChefBank(unverified, false, false);
        insertChefBank(deleted, false, true);

        JsonNode all = body(getAs(admin, "/api/admin/bank-accounts/chefs?search=" + tag).andExpect(status().isOk())).get("data");
        assertThat(all.get("total_rows").asInt()).isEqualTo(2);                                // the deleted one is hidden
        assertThat(all.get("content").get(0).get("id").asLong()).isEqualTo(uId);              // newest first
        JsonNode row = all.get("content").get(1);
        assertThat(row.get("id").asLong()).isEqualTo(vId);
        assertThat(row.get("bank_name").asString()).isEqualTo("Vietcombank");
        assertThat(row.get("bank_code").asString()).isEqualTo("VCB");
        assertThat(row.get("bank_branch").asString()).isEqualTo("HCM");
        assertThat(row.get("account_number").asString()).isEqualTo("00112233");                // renamed like the Django resolver
        assertThat(row.get("account_holder_name").asString()).isEqualTo("NGUYEN VAN CHEF");
        assertThat(row.get("citizen_id").asString()).isEqualTo("079123456789");
        assertThat(row.get("tax_code").asString()).isEqualTo("0312345678");
        assertThat(row.get("is_verified").asBoolean()).isTrue();
        assertThat(row.get("deleted").asBoolean()).isFalse();
        assertThat(row.get("email").asString()).isEqualTo(verified.email());
        assertThat(row.get("user").get("id").asLong()).isEqualTo(verified.userId());
        assertThat(row.get("user").get("username").asString()).isEqualTo(verified.username());
        assertThat(row.get("user").get("first_name").asString()).isEqualTo("Hoa");
        assertThat(row.get("user").get("last_name").asString()).isEqualTo("Pham");

        assertThat(body(getAs(admin, "/api/admin/bank-accounts/chefs?search=" + tag + "&status=true"))
                .get("data").get("content")).extracting(n -> n.get("id").asLong()).containsExactly(vId);
        assertThat(body(getAs(admin, "/api/admin/bank-accounts/chefs?search=" + tag + "&status=false"))
                .get("data").get("content")).extracting(n -> n.get("id").asLong()).containsExactly(uId);
        // search also matches first / last name and username (not just email)
        assertThat(body(getAs(admin, "/api/admin/bank-accounts/chefs?search=pham")).get("data").get("content"))
                .extracting(n -> n.get("id").asLong()).contains(vId);
        assertThat(body(getAs(admin, "/api/admin/bank-accounts/chefs?search=" + unverified.username()))
                .get("data").get("total_rows").asInt()).isEqualTo(1);
        // pagination
        JsonNode p2 = body(getAs(admin, "/api/admin/bank-accounts/chefs?search=" + tag + "&page_size=1&page=2")).get("data");
        assertThat(p2.get("total_pages").asInt()).isEqualTo(2);
        assertThat(p2.get("content")).hasSize(1);
        assertThat(p2.get("content").get(0).get("id").asLong()).isEqualTo(vId);
    }

    private CustomerPaymentInfo customerBank(Account customer, boolean verified) {
        return customerPaymentInfoRepository.save(CustomerPaymentInfo.builder().userId(customer.userId())
                .bankName("ACB").bankCode("ACB").bankAccountNumber("998877").bankAccountName("TRAN THI CUST")
                .bankBranch("Q1").verified(verified).build());
    }

    @Test
    void customerBankAccounts_list_filtersAndShape() throws Exception {
        Account admin = register("bkcu-admin", UserRole.ADMIN);
        String tag = "bku" + System.nanoTime();
        Account a = tagged(tag, "a", UserRole.CUSTOMER);
        Account b = tagged(tag, "b", UserRole.CUSTOMER);
        setName(a, "Mai", "Le");
        CustomerPaymentInfo ia = customerBank(a, true);
        CustomerPaymentInfo ib = customerBank(b, false);

        JsonNode all = body(getAs(admin, "/api/admin/bank-accounts/customers?search=" + tag).andExpect(status().isOk())).get("data");
        assertThat(all.get("total_rows").asInt()).isEqualTo(2);
        assertThat(all.get("total_pages").asInt()).isEqualTo(1);
        assertThat(all.get("content").get(0).get("id").asLong()).isEqualTo(ib.getId());       // newest first
        JsonNode row = all.get("content").get(1);
        assertThat(row.get("id").asLong()).isEqualTo(ia.getId());
        assertThat(row.get("account_number").asString()).isEqualTo("998877");
        assertThat(row.get("account_holder_name").asString()).isEqualTo("TRAN THI CUST");
        assertThat(row.get("bank_branch").asString()).isEqualTo("Q1");
        assertThat(row.get("email").asString()).isEqualTo(a.email());
        assertThat(row.get("user").get("first_name").asString()).isEqualTo("Mai");
        assertThat(row.has("citizen_id")).isFalse();                                          // customer schema has no chef fields
        assertThat(row.has("deleted")).isFalse();

        assertThat(body(getAs(admin, "/api/admin/bank-accounts/customers?search=" + tag + "&status=false"))
                .get("data").get("content")).extracting(n -> n.get("id").asLong()).containsExactly(ib.getId());
        assertThat(body(getAs(admin, "/api/admin/bank-accounts/customers?search=" + tag + "&status=true"))
                .get("data").get("content")).extracting(n -> n.get("id").asLong()).containsExactly(ia.getId());
        assertThat(body(getAs(admin, "/api/admin/bank-accounts/customers?search=le")).get("data").get("total_rows").asInt())
                .isGreaterThanOrEqualTo(1);                                                   // last-name search
        JsonNode p2 = body(getAs(admin, "/api/admin/bank-accounts/customers?search=" + tag + "&page_size=1&page=3")).get("data");
        assertThat(p2.get("content")).isEmpty();                                              // past the end
        assertThat(p2.get("total_pages").asInt()).isEqualTo(2);
        getAs(admin, "/api/admin/bank-accounts/customers?status=perhaps").andExpect(status().isUnauthorized());
    }

    @Test
    void verifyBankAccount_setsFlagAndTimestamp_forCustomerAndChef_withEachRoutesSchema() throws Exception {
        Account admin = register("vb-admin", UserRole.ADMIN);
        Account cust = register("vb-cust", UserRole.CUSTOMER);
        Account chef = register("vb-chef", UserRole.CHEF);
        CustomerPaymentInfo ci = customerBank(cust, false);
        long chefBank = insertChefBank(chef, false, false);

        JsonNode c = body(patchJson(admin, "/api/admin/bank-accounts/customers/" + ci.getId() + "/verification",
                "{\"status\":true}").andExpect(status().isOk())).get("data");
        assertThat(c.get("id").asLong()).isEqualTo(ci.getId());
        assertThat(c.get("is_verified").asBoolean()).isTrue();
        assertThat(c.get("verified_at").isNull()).isFalse();
        assertThat(c.get("email").asString()).isEqualTo(cust.email());
        assertThat(c.has("citizen_id")).isFalse();
        assertThat(customerPaymentInfoRepository.findById(ci.getId()).orElseThrow().isVerified()).isTrue();

        JsonNode c2 = body(patchJson(admin, "/api/admin/bank-accounts/customers/" + ci.getId() + "/verification",
                "{\"status\":false}").andExpect(status().isOk())).get("data");
        assertThat(c2.get("is_verified").asBoolean()).isFalse();
        assertThat(c2.get("verified_at").isNull()).isTrue();                                  // un-verify clears the time

        JsonNode k = body(patchJson(admin, "/api/admin/bank-accounts/chefs/" + chefBank + "/verification",
                "{\"status\":true}").andExpect(status().isOk())).get("data");
        assertThat(k.get("id").asLong()).isEqualTo(chefBank);
        assertThat(k.get("is_verified").asBoolean()).isTrue();
        assertThat(k.get("verified_at").isNull()).isFalse();
        assertThat(k.get("citizen_id").asString()).isEqualTo("079123456789");
        assertThat(k.get("deleted").asBoolean()).isFalse();
        assertThat(k.get("email").asString()).isEqualTo(chef.email());
        Boolean stored = jdbc.queryForObject("select is_verified from chef_payment_info where id = ?", Boolean.class, chefBank);
        assertThat(stored).isTrue();

        // missing / wrong-typed body = validation error
        patchJson(admin, "/api/admin/bank-accounts/chefs/" + chefBank + "/verification", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void verifyBankAccount_looksInTheCustomerTableFirst_soAnOverlappingIdNeverReachesTheChefRow_andDeletedChefIs404() throws Exception {
        Account admin = register("vb2-admin", UserRole.ADMIN);
        Account cust = register("vb2-cust", UserRole.CUSTOMER);
        Account chef = register("vb2-chef", UserRole.CHEF);
        Account deletedChef = register("vb2-del", UserRole.CHEF);
        long chefBank = insertChefBank(chef, false, false);
        // a customer account that happens to share the chef account's id
        jdbc.update("insert into customer_payment_info (id, user_id, bank_name, bank_code, bank_account_number, "
                + "bank_account_name, is_verified) values (?,?,?,?,?,?,?)", chefBank, cust.userId(), "ACB", "ACB", "1", "C", false);

        JsonNode r = body(patchJson(admin, "/api/admin/bank-accounts/chefs/" + chefBank + "/verification",
                "{\"status\":true}").andExpect(status().isOk())).get("data");
        assertThat(r.get("email").asString()).isEqualTo(cust.email());                        // the customer row answered
        assertThat(r.has("citizen_id")).isTrue();                                             // ...through the chef route's schema
        assertThat(r.get("citizen_id").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select is_verified from chef_payment_info where id = ?", Boolean.class, chefBank)).isFalse();
        assertThat(jdbc.queryForObject("select is_verified from customer_payment_info where id = ?", Boolean.class, chefBank)).isTrue();

        long deletedId = insertChefBank(deletedChef, false, true);
        JsonNode nf = body(patchJson(admin, "/api/admin/bank-accounts/chefs/" + deletedId + "/verification",
                "{\"status\":true}").andExpect(status().isNotFound()));
        assertThat(nf.get("message_code").asString()).isEqualTo("HTTP_ERROR");
        assertThat(nf.get("data").asString()).isEqualTo("Bank account not found");
        patchJson(admin, "/api/admin/bank-accounts/customers/987654321/verification", "{\"status\":true}")
                .andExpect(status().isNotFound());
    }
}
