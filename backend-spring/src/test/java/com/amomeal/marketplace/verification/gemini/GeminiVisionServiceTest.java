package com.amomeal.marketplace.verification.gemini;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import com.amomeal.marketplace.verification.gemini.GeminiVisionService.Analysis;
import com.amomeal.marketplace.verification.support.FakeGeminiVisionClient;
import com.amomeal.marketplace.verification.support.FakeImageFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gemini Vision prompts / parsing / failure handling against a fake client (no real Gemini). */
class GeminiVisionServiceTest {

    private FakeGeminiVisionClient client;
    private FakeImageFetcher fetcher;
    private GeminiProperties props;
    private List<Long> sleeps;
    private GeminiVisionService service;

    @BeforeEach
    void setUp() {
        client = new FakeGeminiVisionClient();
        fetcher = new FakeImageFetcher();
        props = new GeminiProperties();
        props.setApiKey("k");
        sleeps = new ArrayList<>();
        service = new GeminiVisionService(client, fetcher, props, JsonMapper.builder().build(), sleeps::add);
    }

    private static String cccdJson(String front, String back, String same, String errors) {
        return """
                {"front": %s, "back": %s, "same_document": %s, "document_errors": %s}
                """.formatted(front, back, same, errors);
    }

    private static final String GOOD_FRONT = """
            {"is_front_side": true, "full_name": "Nguyễn Văn A", "cccd_number": "079123456789",
             "date_of_birth": "1990-01-02", "address": "1 Lê Lợi", "image_clear": true, "possible_editing": false}""";
    private static final String GOOD_BACK = "{\"is_back_side\": true, \"has_qr\": true}";

    @Test
    void cccd_success_shapesExtractedAndSendsBothImagesWithModelAndPrompt() {
        client.respondWith(p -> cccdJson(GOOD_FRONT, GOOD_BACK, "true", "[]"));
        Analysis a = service.analyzeCccd(List.of("http://x/front.jpg", "http://x/back.jpg"));
        assertThat(a.errors()).isEmpty();
        assertThat(a.extracted()).containsEntry("full_name", "Nguyễn Văn A").containsEntry("cccd_number", "079123456789")
                .containsEntry("back_has_qr", true).containsEntry("image_clear", true).containsEntry("possible_editing", false);
        assertThat(client.calls()).hasSize(1);
        assertThat(client.calls().get(0).model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(client.calls().get(0).imageCount()).isEqualTo(2);
        assertThat(client.calls().get(0).prompt()).startsWith("\nBạn nhận được đúng 2 ảnh").contains("CCCD_SIDES_MISMATCH");
        assertThat(fetcher.fetched()).containsExactly("http://x/front.jpg", "http://x/back.jpg");
    }

    @Test
    void cccd_wrongImageCount_isNotFrontWithoutCallingGemini() {
        Analysis a = service.analyzeCccd(List.of("http://x/only.jpg"));
        assertThat(a.extracted()).isNull();
        assertThat(a.errors()).extracting(e -> e.get("code")).containsExactly("CCCD_NOT_FRONT");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void cccd_sidesWrongAndMismatch_areReportedAndDeduplicatedAgainstGeminiErrors() {
        client.respondWith(p -> cccdJson(
                "{\"is_front_side\": false, \"full_name\": \"A\", \"cccd_number\": \"1\"}",
                "{\"is_back_side\": false}", "false", "[\"CCCD_NOT_FRONT\", \"MYSTERY\"]"));
        Analysis a = service.analyzeCccd(List.of("u1", "u2"));
        assertThat(a.errors()).extracting(e -> e.get("code"))
                .containsExactly("CCCD_NOT_FRONT", "MYSTERY", "CCCD_NOT_BACK", "CCCD_SIDES_MISMATCH");
        // unknown code falls back to the code as its message
        assertThat(a.errors().get(1)).containsEntry("message", "MYSTERY");
        assertThat(a.errors().get(0).get("message").toString()).startsWith("Ảnh đầu tiên không phải mặt trước");
    }

    @Test
    void cccd_missingMandatoryFrontFields() {
        client.respondWith(p -> cccdJson("{\"is_front_side\": true}", "{}", "true", "[]"));
        Analysis a = service.analyzeCccd(List.of("u1", "u2"));
        assertThat(a.errors()).extracting(e -> e.get("code"))
                .containsExactly("CCCD_NUMBER_NOT_READABLE", "TEXT_NOT_READABLE");
    }

    @Test
    void business_successAndMissingFields() {
        client.respondWith(p -> """
                {"owner_name": "Nguyễn Văn A", "business_name": "Quán A", "business_license_number": "0123",
                 "address": "1 Lê Lợi", "issue_date": "2020-01-01", "has_signature": false, "document_errors": []}""");
        Analysis ok = service.analyzeBusinessLicense(List.of("u1", "u2"));
        assertThat(ok.extracted()).containsEntry("business_name", "Quán A")
                .containsEntry("has_signature", false)
                .containsEntry("has_red_stamp", false); // absent -> Django default False
        assertThat(client.calls().get(0).imageCount()).isEqualTo(2);

        client.respondWith(p -> "{\"document_errors\": [\"IMAGE_BLURRY\"]}");
        Analysis bad = service.analyzeBusinessLicense(List.of("u1"));
        assertThat(bad.extracted()).isNull();
        assertThat(bad.errors()).extracting(e -> e.get("code"))
                .containsExactly("IMAGE_BLURRY", "OWNER_NAME_NOT_READABLE", "LICENSE_NUMBER_NOT_READABLE");
        assertThat(bad.errors().get(0)).containsEntry("message", "Ảnh giấy phép kinh doanh bị mờ.");
    }

    @Test
    void foodSafety_successAndMissingFields() {
        client.respondWith(p -> """
                {"owner_name": "A", "facility_name": "Bếp A", "certificate_number": "77", "address": "x",
                 "issue_date": "2024-01-01", "expiry_date": "2099-01-01", "has_signature": true, "has_red_stamp": true}""");
        Analysis ok = service.analyzeFoodSafety(List.of("u1"));
        assertThat(ok.extracted()).containsEntry("certificate_number", "77").containsEntry("expiry_date", "2099-01-01");

        client.respondWith(p -> "{}");
        Analysis bad = service.analyzeFoodSafety(List.of("u1"));
        assertThat(bad.errors()).extracting(e -> e.get("code"))
                .containsExactly("CERT_NUMBER_NOT_READABLE", "EXPIRY_DATE_NOT_READABLE");
    }

    @Test
    void selfie_returnsCodeReadAndMappedErrors_promptEmbedsExpectedCode() {
        client.respondWith(p -> """
                {"face_detected": true, "has_id_card": true, "has_verification_code": true,
                 "verification_code_read": "VERIFY-123456", "document_errors": ["IMAGE_BLURRY"]}""");
        var r = service.analyzeSelfie("http://x/s.jpg", "VERIFY-123456");
        assertThat(r).containsEntry("verification_code_read", "VERIFY-123456").containsEntry("face_detected", true);
        assertThat(r.get("errors").toString()).contains("Ảnh selfie bị mờ.");
        assertThat(client.calls().get(0).prompt()).contains("Mã xác thực cần đọc: VERIFY-123456").contains("\"face_detected\": true");
        assertThat(client.calls().get(0).imageCount()).isEqualTo(1);
    }

    @Test
    void emptyApiKey_analysisRaises_likeDjangoUncaughtError() {
        props.setApiKey("");
        assertThatThrownBy(() -> service.analyzeBusinessLicense(List.of("u1"))).isInstanceOf(IllegalStateException.class);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void badJson_nullText_andNonObject_allRaise() {
        client.respondWith(p -> "not json");
        assertThatThrownBy(() -> service.analyzeFoodSafety(List.of("u1"))).isInstanceOf(RuntimeException.class);
        client.respondWith(p -> null);
        assertThatThrownBy(() -> service.analyzeFoodSafety(List.of("u1"))).isInstanceOf(IllegalStateException.class);
        client.respondWith(p -> "[1,2]");
        assertThatThrownBy(() -> service.analyzeFoodSafety(List.of("u1"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rateLimit429_isRetriedWithBackoff_butDailyQuotaIsNot() {
        AtomicInteger n = new AtomicInteger();
        client.respondWith(p -> {
            if (n.incrementAndGet() < 3) {
                throw new GeminiVisionClient.GeminiApiException(429, "429 RESOURCE_EXHAUSTED per minute");
            }
            return "{\"owner_name\":\"A\",\"business_license_number\":\"1\"}";
        });
        assertThat(service.analyzeBusinessLicense(List.of("u1")).errors()).isEmpty();
        assertThat(n.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(1000L, 2000L);

        sleeps.clear();
        n.set(0);
        client.reset();
        client.respondWith(p -> {
            n.incrementAndGet();
            throw new GeminiVisionClient.GeminiApiException(429, "429 quota PerDay exceeded");
        });
        assertThatThrownBy(() -> service.analyzeBusinessLicense(List.of("u1")))
                .isInstanceOf(GeminiVisionClient.GeminiApiException.class);
        assertThat(n.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void persistent429_givesUpAfterThreeAttempts_andOtherStatusesAreNotRetried() {
        AtomicInteger n = new AtomicInteger();
        client.respondWith(p -> {
            n.incrementAndGet();
            throw new GeminiVisionClient.GeminiApiException(429, "429 slow down");
        });
        assertThatThrownBy(() -> service.analyzeFoodSafety(List.of("u1"))).isInstanceOf(GeminiVisionClient.GeminiApiException.class);
        assertThat(n.get()).isEqualTo(3);

        n.set(0);
        client.respondWith(p -> {
            n.incrementAndGet();
            throw new GeminiVisionClient.GeminiApiException(500, "500 boom");
        });
        assertThatThrownBy(() -> service.analyzeFoodSafety(List.of("u1"))).isInstanceOf(GeminiVisionClient.GeminiApiException.class);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void addressComparison_paths() {
        client.respondWith(p -> "{\"same_location\": true, \"confidence\": \"high\", \"reason\": \"r\"}");
        assertThat(service.verifySameAddress("123 Nguyễn Huệ", "123 Nguyễn Huệ, Q.1")).isTrue();
        assertThat(client.calls().get(0).imageCount()).isZero(); // text-only
        assertThat(client.calls().get(0).prompt()).startsWith("So sánh 2 địa chỉ").contains("Địa chỉ A: 123 Nguyễn Huệ");

        client.respondWith(p -> "{\"same_location\": false, \"confidence\": \"high\"}");
        assertThat(service.verifySameAddress("a", "b")).isFalse();
        client.respondWith(p -> "{\"same_location\": false, \"confidence\": \"medium\"}");
        assertThat(service.verifySameAddress("a", "b")).isFalse();
        // low confidence => treated as the same address
        client.respondWith(p -> "{\"same_location\": false, \"confidence\": \"low\"}");
        assertThat(service.verifySameAddress("a", "b")).isTrue();
        // missing confidence defaults to low
        client.respondWith(p -> "{\"same_location\": false}");
        assertThat(service.verifySameAddress("a", "b")).isTrue();
    }

    @Test
    void addressComparison_failuresAreFailSafeMatch() {
        client.respondWith(p -> {
            throw new GeminiVisionClient.GeminiApiException(500, "boom");
        });
        assertThat(service.verifySameAddress("a", "b")).isTrue();
        client.respondWith(p -> "garbage");
        assertThat(service.verifySameAddress("a", "b")).isTrue();
        props.setApiKey("");
        assertThat(service.verifySameAddress("a", "b")).isTrue();
    }

    @Test
    void pythonTruthiness() {
        assertThat(GeminiVisionService.truthy(null)).isFalse();
        assertThat(GeminiVisionService.truthy("")).isFalse();
        assertThat(GeminiVisionService.truthy(0)).isFalse();
        assertThat(GeminiVisionService.truthy(List.of())).isFalse();
        assertThat(GeminiVisionService.truthy("x")).isTrue();
        assertThat(GeminiVisionService.truthy(2)).isTrue();
    }
}
