package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import com.amomeal.marketplace.recommendation.service.GeminiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Port of ../../backend/report/services/gemini_severity.py::analyze_report_severity: classifies a report's
 * severity (LOW/MEDIUM/HIGH/CRITICAL) with Gemini (model {@code gemini-2.5-flash-lite}) through the existing
 * text {@link GeminiClient} seam (the same one recommendation uses; tests substitute a fake - the real API is
 * never called from tests).
 *
 * <p><b>Never throws</b> (Django's contract): an empty API key (Django: {@code genai.Client(api_key="")} raises), a
 * transport/HTTP error, a null/non-JSON/non-object response ... all fall back to
 * {@code severity=LOW, food_safety_risk=false, confidence=low, reason="Không thể phân tích tự động."}. Only a
 * per-minute 429 is retried (3 attempts, sleeping 1 s then 2 s); a per-day 429 or any other error is not.
 */
@Slf4j
@Service
public class GeminiSeverityService {

    /** Django's result dict. {@code severity} is the raw string Gemini returned (may be null/unknown). */
    public record SeverityResult(String severity, boolean foodSafetyRisk, List<Object> keywordsDetected,
                                 String confidence, String reason) {
    }

    static final String FALLBACK_REASON = "Không thể phân tích tự động.";

    private static final String PROMPT = """
            Phân tích nội dung phản ánh về chất lượng thức ăn của khách hàng và phân loại mức độ nghiêm trọng.

            Danh mục phản ánh: %s
            Mô tả của khách: %s

            Trả về JSON đúng cấu trúc sau, không kèm markdown:
            {
              "severity": "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
              "food_safety_risk": true | false,
              "keywords_detected": ["<từ khóa 1>", "<từ khóa 2>"],
              "confidence": "high" | "medium" | "low",
              "reason": "<giải thích ngắn dưới 2 câu>"
            }

            Quy tắc phân loại:
            - CRITICAL: Dị vật nguy hiểm (tóc, kim loại, kính, sâu), mốc nhìn thấy được, nghi ngờ ngộ độc thực phẩm.
            - HIGH: Thức ăn hôi thối, hư thiu, ôi, tanh nặng — có nguy cơ gây hại cho sức khỏe nếu ăn.
            - MEDIUM: Sạn/cát trong thức ăn, thức ăn tái/sống nhẹ, vệ sinh bao bì kém, mùi lạ nhẹ.
            - LOW: Không ngon, ít đồ, sai món, thiếu gia vị, giao nhầm — không ảnh hưởng sức khỏe.

            food_safety_risk = true khi severity là HIGH hoặc CRITICAL.
            keywords_detected: liệt kê các từ/cụm từ liên quan đến an toàn thực phẩm tìm thấy trong mô tả.
            """;

    private final GeminiClient gemini;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final LongConsumer sleeper;

    @Autowired
    public GeminiSeverityService(GeminiClient gemini, GeminiProperties properties, ObjectMapper objectMapper) {
        this(gemini, properties, objectMapper, GeminiSeverityService::sleepMillis);
    }

    /** Test seam: replaces Thread.sleep so the 429 retry path runs instantly. */
    GeminiSeverityService(GeminiClient gemini, GeminiProperties properties, ObjectMapper objectMapper,
                          LongConsumer sleeper) {
        this.gemini = gemini;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    public SeverityResult analyze(String category, String description) {
        try {
            String apiKey = properties.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("GEMINI_API_KEY is empty");
            }
            String prompt = PROMPT.formatted(category, description);
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    String text = gemini.generateContent(apiKey, properties.getModel(), prompt, null);
                    return parse(text);
                } catch (HttpClientErrorException ex) {
                    String message = ex.getMessage() + " " + ex.getResponseBodyAsString();
                    boolean is429 = ex.getStatusCode().value() == 429 || message.contains("429");
                    boolean isDaily = message.contains("PerDay") || message.contains("per_day");
                    if (is429 && !isDaily && attempt < 2) {
                        sleeper.accept(1000L * (1L << attempt));
                        continue;
                    }
                    throw ex;
                }
            }
            throw new IllegalStateException("unreachable");
        } catch (Exception ex) {
            log.warn("Gemini severity analysis failed, defaulting to LOW: {}", ex.toString());
            return fallback();
        }
    }

    static SeverityResult fallback() {
        return new SeverityResult("LOW", false, List.of(), "low", FALLBACK_REASON);
    }

    /** json.loads + the .get(...) defaults; anything that is not a JSON object raises (-> fallback). */
    private SeverityResult parse(String text) {
        if (text == null) {
            throw new IllegalStateException("empty Gemini response");
        }
        JsonNode root = objectMapper.readTree(text);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Gemini response is not a JSON object");
        }
        JsonNode sev = root.get("severity");
        String severity = !root.has("severity") ? "LOW" : (sev.isNull() ? null : asText(sev));
        boolean risk = root.has("food_safety_risk") && truthy(root.get("food_safety_risk"));
        List<Object> keywords = new ArrayList<>();
        if (root.has("keywords_detected") && root.get("keywords_detected").isArray()) {
            root.get("keywords_detected").forEach(k -> keywords.add(asText(k)));
        }
        String confidence = root.has("confidence") ? asText(root.get("confidence")) : "low";
        String reason = root.has("reason") ? asText(root.get("reason")) : "";
        return new SeverityResult(severity, risk, keywords, confidence, reason);
    }

    private static String asText(JsonNode n) {
        return n.isString() ? n.asString() : n.toString();
    }

    /** Python bool(x) of a JSON value. */
    private static boolean truthy(JsonNode n) {
        if (n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.asDouble() != 0.0;
        }
        if (n.isString()) {
            return !n.asString().isEmpty();
        }
        return n.size() > 0;
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
