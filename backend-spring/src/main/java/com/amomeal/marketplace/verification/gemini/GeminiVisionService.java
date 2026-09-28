package com.amomeal.marketplace.verification.gemini;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Port of ../../backend/verification/services/gemini.py (GeminiVisionService): OCR + document
 * quality checks + selfie code reading. Prompts, error tables, model name and result shaping are
 * 1:1. Gemini only extracts; every business decision is made by the callers.
 *
 * <p>Failure behavior mirrors Django exactly: an empty {@code GEMINI_API_KEY}, a transport/API
 * failure, a null/invalid-JSON/non-object response all raise (uncaught in Django -> HTTP 500
 * CONTACT_ADMIN_FOR_SUPPORT); only 429 per-minute rate limits are retried (2^attempt s, 3 attempts).
 * The one fail-safe path is {@link #verifySameAddress} (any failure -> "same").
 */
@Slf4j
@Service
public class GeminiVisionService {

    // ── Error tables ─────────────────────────────────────────────────────────

    private static final Map<String, String> CCCD_ERRORS = Map.of(
            "IMAGE_BLURRY", "Ảnh CCCD bị mờ, không đọc được nội dung.",
            "CCCD_NUMBER_NOT_READABLE", "Không đọc được số CCCD.",
            "PORTRAIT_MISSING", "Không tìm thấy ảnh chân dung trên CCCD.",
            "TEXT_NOT_READABLE", "Chữ trên CCCD không đọc được.",
            "WRONG_DOCUMENT_TYPE", "Đây không phải là CCCD Việt Nam.",
            "CCCD_NOT_FRONT", "Ảnh đầu tiên không phải mặt trước CCCD. Vui lòng tải đúng thứ tự: mặt trước trước, mặt sau sau.",
            "CCCD_NOT_BACK", "Ảnh thứ hai không phải mặt sau CCCD. Vui lòng tải đúng thứ tự: mặt trước trước, mặt sau sau.",
            "CCCD_SIDES_MISMATCH", "Mặt trước và mặt sau không thuộc cùng một CCCD (số CCCD không khớp). Vui lòng kiểm tra và tải lại.");

    private static final Map<String, String> BUSINESS_ERRORS = Map.of(
            "IMAGE_BLURRY", "Ảnh giấy phép kinh doanh bị mờ.",
            "OWNER_NAME_NOT_READABLE", "Không đọc được tên chủ hộ kinh doanh.",
            "LICENSE_NUMBER_NOT_READABLE", "Không đọc được số giấy phép kinh doanh.",
            "CONTENT_CROPPED", "Giấy tờ bị cắt mất nội dung quan trọng.",
            "WRONG_DOCUMENT_TYPE", "Đây không phải là giấy đăng ký kinh doanh.");

    private static final Map<String, String> FOOD_SAFETY_ERRORS = Map.of(
            "IMAGE_BLURRY", "Ảnh chứng nhận ATTP quá mờ.",
            "CERT_NUMBER_NOT_READABLE", "Không đọc được số chứng nhận ATTP.",
            "EXPIRY_DATE_NOT_READABLE", "Không đọc được ngày hết hạn.",
            "WRONG_DOCUMENT_TYPE", "Đây không phải là giấy chứng nhận an toàn thực phẩm.");

    private static final Map<String, String> SELFIE_ERRORS = Map.of(
            "FACE_NOT_DETECTED", "Không phát hiện khuôn mặt trong ảnh.",
            "ID_CARD_NOT_VISIBLE", "Không nhìn thấy CCCD trong ảnh selfie.",
            "VERIFICATION_CODE_NOT_VISIBLE", "Không thấy tờ giấy có mã xác thực trong ảnh.",
            "IMAGE_BLURRY", "Ảnh selfie bị mờ.");

    // ── Prompts (byte-for-byte from gemini.py; Python triple-quoted strings start with a newline) ──

    static final String CCCD_PAIR_PROMPT = "\n" + """
            Bạn nhận được đúng 2 ảnh theo thứ tự: ảnh 1 = mặt TRƯỚC CCCD, ảnh 2 = mặt SAU CCCD.
            Hãy phân tích và xác nhận từng mặt, sau đó kiểm tra 2 mặt có thuộc cùng một CCCD không.

            Trả về JSON đúng cấu trúc sau, không kèm markdown:
            {
              "front": {
                "is_front_side": true,
                "full_name": "<họ tên đầy đủ hoặc null>",
                "cccd_number": "<12 chữ số hoặc null>",
                "date_of_birth": "<YYYY-MM-DD hoặc null>",
                "address": "<địa chỉ thường trú hoặc null>",
                "has_portrait": true,
                "image_clear": true,
                "text_readable": true,
                "possible_editing": false
              },
              "back": {
                "is_back_side": true,
                "cccd_number_on_back": "<12 chữ số đọc từ mặt sau hoặc null>",
                "has_qr": true,
                "image_clear": true
              },
              "same_document": true,
              "document_errors": []
            }

            Quy tắc xác định mặt trước/sau:
            - Mặt TRƯỚC: có ảnh chân dung, họ tên, số CCCD, ngày sinh, địa chỉ thường trú, quốc huy
            - Mặt SAU: có chip điện tử, mã QR, vân tay điện tử, không có ảnh chân dung

            same_document = false nếu:
            - Số CCCD đọc được từ mặt trước và mặt sau KHÁC nhau (cả 2 đọc được mà khác)

            Chỉ thêm mã lỗi vào document_errors khi thực sự gặp phải:
            "IMAGE_BLURRY" | "CCCD_NUMBER_NOT_READABLE" | "PORTRAIT_MISSING" | "TEXT_NOT_READABLE" |
            "WRONG_DOCUMENT_TYPE" | "CCCD_NOT_FRONT" | "CCCD_NOT_BACK" | "CCCD_SIDES_MISMATCH"
            """;

    static final String BUSINESS_PROMPT = "\n" + """
            Phân tích ảnh Giấy đăng ký hộ kinh doanh / Giấy phép kinh doanh Việt Nam.
            Trả về JSON đúng cấu trúc sau, không kèm markdown:
            {
              "owner_name": "<tên chủ hộ kinh doanh hoặc null>",
              "business_name": "<tên hộ/cơ sở kinh doanh hoặc null>",
              "business_license_number": "<số giấy phép hoặc null>",
              "address": "<địa chỉ kinh doanh hoặc null>",
              "issue_date": "<YYYY-MM-DD hoặc null>",
              "has_signature": true,
              "has_red_stamp": true,
              "document_errors": []
            }
            Chỉ thêm mã lỗi vào document_errors khi thực sự gặp phải:
            "IMAGE_BLURRY" | "OWNER_NAME_NOT_READABLE" | "LICENSE_NUMBER_NOT_READABLE" | "CONTENT_CROPPED" | "WRONG_DOCUMENT_TYPE"
            """;

    static final String FOOD_SAFETY_PROMPT = "\n" + """
            Phân tích ảnh Giấy chứng nhận An toàn vệ sinh thực phẩm Việt Nam.
            Trả về JSON đúng cấu trúc sau, không kèm markdown:
            {
              "owner_name": "<tên chủ cơ sở hoặc null>",
              "facility_name": "<tên cơ sở kinh doanh hoặc null>",
              "certificate_number": "<số chứng nhận hoặc null>",
              "address": "<địa chỉ cơ sở hoặc null>",
              "issue_date": "<YYYY-MM-DD hoặc null>",
              "expiry_date": "<YYYY-MM-DD hoặc null>",
              "has_signature": true,
              "has_red_stamp": true,
              "document_errors": []
            }
            Chỉ thêm mã lỗi vào document_errors khi thực sự gặp phải:
            "IMAGE_BLURRY" | "CERT_NUMBER_NOT_READABLE" | "EXPIRY_DATE_NOT_READABLE" | "WRONG_DOCUMENT_TYPE"
            """;

    static String selfiePrompt(String expectedCode) {
        return "\n" + """
                Phân tích ảnh selfie này. Người chụp đang cầm CCCD và một tờ giấy có mã xác thực.
                Mã xác thực cần đọc: %s
                Trả về JSON đúng cấu trúc sau, không kèm markdown:
                {
                  "face_detected": true,
                  "has_id_card": true,
                  "has_verification_code": true,
                  "verification_code_read": "<mã đọc được hoặc null>",
                  "document_errors": []
                }
                Chỉ thêm mã lỗi vào document_errors khi thực sự gặp phải:
                "FACE_NOT_DETECTED" | "ID_CARD_NOT_VISIBLE" | "VERIFICATION_CODE_NOT_VISIBLE" | "IMAGE_BLURRY"
                """.formatted(expectedCode);
    }

    static String addressPrompt(String a, String b) {
        return """
                So sánh 2 địa chỉ sau đây và xác định chúng có cùng một địa điểm thực tế không.

                Địa chỉ A: %s
                Địa chỉ B: %s

                Trả về JSON:
                {"same_location": true/false, "confidence": "high"/"medium"/"low", "reason": "<giải thích ngắn>"}

                Các trường hợp cần coi là CÙNG địa chỉ:
                - Một địa chỉ đầy đủ hơn địa chỉ kia (VD: "123 Nguyễn Huệ" và "123 Nguyễn Huệ, P. Bến Nghé, Q.1")
                - Mô tả khác nhau cho cùng nơi (VD: "Nhà riêng" và "Địa điểm sản xuất tại nhà")
                - Cách viết khác nhau (VD: "Quận 1" và "Q.1")

                Chỉ trả về false khi rõ ràng là 2 địa điểm khác nhau (số nhà khác, đường khác, quận/tỉnh khác).""".formatted(a, b);
    }

    /** {@code {"extracted": ..., "errors": [...]}} of Django's analyze_* functions. */
    public record Analysis(Map<String, Object> extracted, List<Map<String, Object>> errors) {
    }

    private final GeminiVisionClient client;
    private final ImageFetcher fetcher;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final LongConsumer sleeper;

    @Autowired
    public GeminiVisionService(GeminiVisionClient client, ImageFetcher fetcher, GeminiProperties properties,
                               ObjectMapper objectMapper) {
        this(client, fetcher, properties, objectMapper, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public GeminiVisionService(GeminiVisionClient client, ImageFetcher fetcher, GeminiProperties properties,
                               ObjectMapper objectMapper, LongConsumer sleeper) {
        this.client = client;
        this.fetcher = fetcher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    // ── Python-semantics helpers ─────────────────────────────────────────────

    /** Python truthiness of a JSON value. */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof CharSequence s) {
            return s.length() > 0;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** {@code d.get(key, default)}: the default applies only when the key is ABSENT (null stays null). */
    static Object get(Map<String, Object> d, String key, Object dflt) {
        return d.containsKey(key) ? d.get(key) : dflt;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return truthy(o) && o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static List<String> codes(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object x : c) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> buildErrors(List<String> codes, Map<String, String> table) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : codes) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", code);
            e.put("message", table.getOrDefault(code, code));
            out.add(e);
        }
        return out;
    }

    /** {@code gemini_errors + [e for e in extra if e not in gemini_errors]} (dict equality == same code). */
    private static List<Map<String, Object>> merge(List<Map<String, Object>> gemini, List<Map<String, Object>> extra) {
        List<Map<String, Object>> all = new ArrayList<>(gemini);
        for (Map<String, Object> e : extra) {
            if (!gemini.contains(e)) {
                all.add(e);
            }
        }
        return all;
    }

    // ── Transport ────────────────────────────────────────────────────────────

    private List<GeminiVisionClient.InlineImage> downloadAll(List<String> urls) {
        List<GeminiVisionClient.InlineImage> images = new ArrayList<>();
        for (String url : urls) {
            ImageFetcher.FetchedImage fetched = fetcher.fetch(url);
            images.add(new GeminiVisionClient.InlineImage(fetched.data(), fetched.mimeType()));
        }
        return images;
    }

    private String requireApiKey() {
        String key = properties.getApiKey();
        if (key == null || key.isBlank()) {
            // genai.Client(api_key="") raises ValueError in Django -> uncaught -> HTTP 500
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }
        return key;
    }

    /** Django _call_gemini: up to 3 attempts, retrying only per-minute 429s. */
    Map<String, Object> callGemini(List<GeminiVisionClient.InlineImage> images, String prompt) {
        String apiKey = requireApiKey();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String text = client.generateJson(apiKey, properties.getModel(), images, prompt);
                return parseObject(text);
            } catch (GeminiVisionClient.GeminiApiException e) {
                boolean is429 = e.getStatus() == 429 || String.valueOf(e.getMessage()).contains("429");
                boolean isDaily = String.valueOf(e.getMessage()).contains("PerDay")
                        || String.valueOf(e.getMessage()).contains("per_day");
                if (is429 && !isDaily && attempt < 2) {
                    sleeper.accept((1L << attempt) * 1000L);
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("Gemini returned no result");
    }

    /** json.loads(response.text) then dict access: null / invalid JSON / non-object all raise. */
    private Map<String, Object> parseObject(String text) {
        if (text == null) {
            throw new IllegalStateException("Gemini returned no text");
        }
        Object parsed = objectMapper.readValue(text, Object.class);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalStateException("Gemini JSON is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Exactly 2 URLs: [front, back]. */
    public Analysis analyzeCccd(List<String> publicUrls) {
        if (publicUrls.size() != 2) {
            return new Analysis(null, buildErrors(List.of("CCCD_NOT_FRONT"), CCCD_ERRORS));
        }
        Map<String, Object> result = callGemini(downloadAll(publicUrls), CCCD_PAIR_PROMPT);

        List<Map<String, Object>> geminiErrors = buildErrors(codes(get(result, "document_errors", List.of())), CCCD_ERRORS);
        Map<String, Object> front = asMap(result.get("front"));
        Map<String, Object> back = asMap(result.get("back"));

        List<String> extra = new ArrayList<>();
        if (!truthy(get(front, "is_front_side", true))) {
            extra.add("CCCD_NOT_FRONT");
        }
        if (!truthy(get(back, "is_back_side", true))) {
            extra.add("CCCD_NOT_BACK");
        }
        if (!truthy(get(result, "same_document", true))) {
            extra.add("CCCD_SIDES_MISMATCH");
        }
        if (!truthy(front.get("cccd_number"))) {
            extra.add("CCCD_NUMBER_NOT_READABLE");
        }
        if (!truthy(front.get("full_name"))) {
            extra.add("TEXT_NOT_READABLE");
        }
        List<Map<String, Object>> all = merge(geminiErrors, buildErrors(extra, CCCD_ERRORS));
        if (!all.isEmpty()) {
            return new Analysis(null, all);
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("full_name", front.get("full_name"));
        extracted.put("cccd_number", front.get("cccd_number"));
        extracted.put("date_of_birth", front.get("date_of_birth"));
        extracted.put("address", front.get("address"));
        extracted.put("image_clear", get(front, "image_clear", true));
        extracted.put("possible_editing", get(front, "possible_editing", false));
        extracted.put("back_has_qr", get(back, "has_qr", false));
        return new Analysis(extracted, List.of());
    }

    public Analysis analyzeBusinessLicense(List<String> publicUrls) {
        Map<String, Object> result = callGemini(downloadAll(publicUrls), BUSINESS_PROMPT);
        List<Map<String, Object>> geminiErrors = buildErrors(codes(get(result, "document_errors", List.of())), BUSINESS_ERRORS);
        List<String> extra = new ArrayList<>();
        if (!truthy(result.get("owner_name"))) {
            extra.add("OWNER_NAME_NOT_READABLE");
        }
        if (!truthy(result.get("business_license_number"))) {
            extra.add("LICENSE_NUMBER_NOT_READABLE");
        }
        List<Map<String, Object>> all = merge(geminiErrors, buildErrors(extra, BUSINESS_ERRORS));
        if (!all.isEmpty()) {
            return new Analysis(null, all);
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("owner_name", result.get("owner_name"));
        extracted.put("business_name", result.get("business_name"));
        extracted.put("business_license_number", result.get("business_license_number"));
        extracted.put("address", result.get("address"));
        extracted.put("issue_date", result.get("issue_date"));
        extracted.put("has_signature", get(result, "has_signature", false));
        extracted.put("has_red_stamp", get(result, "has_red_stamp", false));
        return new Analysis(extracted, List.of());
    }

    public Analysis analyzeFoodSafety(List<String> publicUrls) {
        Map<String, Object> result = callGemini(downloadAll(publicUrls), FOOD_SAFETY_PROMPT);
        List<Map<String, Object>> geminiErrors = buildErrors(codes(get(result, "document_errors", List.of())), FOOD_SAFETY_ERRORS);
        List<String> extra = new ArrayList<>();
        if (!truthy(result.get("certificate_number"))) {
            extra.add("CERT_NUMBER_NOT_READABLE");
        }
        if (!truthy(result.get("expiry_date"))) {
            extra.add("EXPIRY_DATE_NOT_READABLE");
        }
        List<Map<String, Object>> all = merge(geminiErrors, buildErrors(extra, FOOD_SAFETY_ERRORS));
        if (!all.isEmpty()) {
            return new Analysis(null, all);
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("owner_name", result.get("owner_name"));
        extracted.put("facility_name", result.get("facility_name"));
        extracted.put("certificate_number", result.get("certificate_number"));
        extracted.put("address", result.get("address"));
        extracted.put("issue_date", result.get("issue_date"));
        extracted.put("expiry_date", result.get("expiry_date"));
        extracted.put("has_signature", get(result, "has_signature", false));
        extracted.put("has_red_stamp", get(result, "has_red_stamp", false));
        return new Analysis(extracted, List.of());
    }

    /**
     * Full Gemini selfie result: face_detected, has_id_card, has_verification_code,
     * verification_code_read, errors. Code matching is the caller's job.
     */
    public Map<String, Object> analyzeSelfie(String publicUrl, String expectedCode) {
        Map<String, Object> result = callGemini(downloadAll(List.of(publicUrl)), selfiePrompt(expectedCode));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("face_detected", get(result, "face_detected", false));
        out.put("has_id_card", get(result, "has_id_card", false));
        out.put("has_verification_code", get(result, "has_verification_code", false));
        out.put("verification_code_read", result.get("verification_code_read"));
        out.put("errors", buildErrors(codes(get(result, "document_errors", List.of())), SELFIE_ERRORS));
        return out;
    }

    /**
     * Text-only Gemini address comparison. Fail-safe like Django: any failure (including an empty
     * API key) or "low" confidence counts as "same address".
     */
    public boolean verifySameAddress(String addressA, String addressB) {
        try {
            String apiKey = requireApiKey();
            String text = client.generateJson(apiKey, properties.getModel(), List.of(), addressPrompt(addressA, addressB));
            Map<String, Object> result = parseObject(text);
            Object same = get(result, "same_location", true);
            Object confidence = get(result, "confidence", "low");
            log.info("Address comparison: same={} confidence={}", same, confidence);
            if ("low".equals(confidence)) {
                return true;
            }
            return truthy(same);
        } catch (RuntimeException e) {
            log.warn("Address Gemini comparison failed, defaulting to match: {}", e.getMessage());
            return true;
        }
    }
}
