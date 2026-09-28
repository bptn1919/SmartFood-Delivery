package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import com.amomeal.marketplace.recommendation.support.FakeGeminiClient;
import com.amomeal.marketplace.report.service.GeminiSeverityService.SeverityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Gemini severity classifier over a FAKE Gemini client (the real API is never called). */
class GeminiSeverityServiceTest {

    FakeGeminiClient fake;
    GeminiProperties props;
    List<Long> sleeps;
    GeminiSeverityService service;

    @BeforeEach
    void setUp() {
        fake = new FakeGeminiClient();
        props = new GeminiProperties();
        props.setApiKey("test-key");
        sleeps = new ArrayList<>();
        service = new GeminiSeverityService(fake, props, JsonMapper.builder().build(), sleeps::add);
    }

    @Test
    void parsesGeminiJson_andSendsCategoryAndDescriptionInThePrompt() {
        fake.respondWith(p -> """
                {"severity":"CRITICAL","food_safety_risk":true,"keywords_detected":["tóc"],"confidence":"high","reason":"Có tóc."}
                """);

        SeverityResult r = service.analyze("FOOD_SAFETY", "Tôi thấy tóc trong món");

        assertThat(r.severity()).isEqualTo("CRITICAL");
        assertThat(r.foodSafetyRisk()).isTrue();
        assertThat(r.keywordsDetected()).containsExactly("tóc");
        assertThat(r.confidence()).isEqualTo("high");
        assertThat(r.reason()).isEqualTo("Có tóc.");
        assertThat(fake.calls()).hasSize(1);
        assertThat(fake.calls().get(0).model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(fake.calls().get(0).apiKey()).isEqualTo("test-key");
        assertThat(fake.calls().get(0).timeout()).isNull();
        assertThat(fake.calls().get(0).prompt())
                .contains("Danh mục phản ánh: FOOD_SAFETY")
                .contains("Mô tả của khách: Tôi thấy tóc trong món");
    }

    @Test
    void missingFields_useDjangoDefaults() {
        fake.respondWith(p -> "{}");

        SeverityResult r = service.analyze("FOOD_QUALITY", "x");

        assertThat(r.severity()).isEqualTo("LOW");
        assertThat(r.foodSafetyRisk()).isFalse();
        assertThat(r.keywordsDetected()).isEmpty();
        assertThat(r.confidence()).isEqualTo("low");
        assertThat(r.reason()).isEmpty();
    }

    @Test
    void foodSafetyRisk_usesPythonTruthiness() {
        fake.respondWith(p -> "{\"severity\":\"HIGH\",\"food_safety_risk\":\"yes\"}");
        assertThat(service.analyze("HYGIENE", "x").foodSafetyRisk()).isTrue();
        fake.respondWith(p -> "{\"severity\":\"HIGH\",\"food_safety_risk\":0}");
        assertThat(service.analyze("HYGIENE", "x").foodSafetyRisk()).isFalse();
        fake.respondWith(p -> "{\"severity\":\"HIGH\",\"food_safety_risk\":null}");
        assertThat(service.analyze("HYGIENE", "x").foodSafetyRisk()).isFalse();
    }

    @Test
    void emptyApiKey_fallsBackToLow_withoutCallingGemini() {
        props.setApiKey("");

        SeverityResult r = service.analyze("FOOD_SAFETY", "có tóc");

        assertFallback(r);
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void clientException_fallsBackToLow() {
        fake.respondWith(p -> {
            throw new IllegalStateException("boom");
        });
        assertFallback(service.analyze("FOOD_SAFETY", "có tóc"));
    }

    @Test
    void nullInvalidOrNonObjectJson_fallsBackToLow() {
        fake.respondWith(p -> null);
        assertFallback(service.analyze("FOOD_SAFETY", "x"));
        fake.respondWith(p -> "not json at all");
        assertFallback(service.analyze("FOOD_SAFETY", "x"));
        fake.respondWith(p -> "[1,2]");
        assertFallback(service.analyze("FOOD_SAFETY", "x"));
    }

    @Test
    void perMinute429_isRetriedWithBackoff_thenSucceeds() {
        int[] n = {0};
        fake.respondWith(p -> {
            if (n[0]++ < 2) {
                throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", null, "quota".getBytes(), null);
            }
            return "{\"severity\":\"MEDIUM\",\"food_safety_risk\":false}";
        });

        SeverityResult r = service.analyze("HYGIENE", "x");

        assertThat(r.severity()).isEqualTo("MEDIUM");
        assertThat(fake.calls()).hasSize(3);
        assertThat(sleeps).containsExactly(1000L, 2000L);
    }

    @Test
    void perMinute429_thatNeverClears_givesUpAfterThreeAttemptsAndFallsBack() {
        fake.respondWith(p -> {
            throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", null, "quota".getBytes(), null);
        });

        assertFallback(service.analyze("HYGIENE", "x"));
        assertThat(fake.calls()).hasSize(3);
    }

    @Test
    void perDay429_isNotRetried() {
        fake.respondWith(p -> {
            throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", null,
                    "GenerateRequestsPerDayPerProjectPerModel".getBytes(), null);
        });

        assertFallback(service.analyze("HYGIENE", "x"));
        assertThat(fake.calls()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void other4xx_isNotRetried() {
        fake.respondWith(p -> {
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", null, "bad".getBytes(), null);
        });

        assertFallback(service.analyze("HYGIENE", "x"));
        assertThat(fake.calls()).hasSize(1);
    }

    private static void assertFallback(SeverityResult r) {
        assertThat(r.severity()).isEqualTo("LOW");
        assertThat(r.foodSafetyRisk()).isFalse();
        assertThat(r.keywordsDetected()).isEmpty();
        assertThat(r.confidence()).isEqualTo("low");
        assertThat(r.reason()).isEqualTo("Không thể phân tích tự động.");
    }
}
