package com.amomeal.marketplace.verification.gemini;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini REST implementation of {@link GeminiVisionClient}:
 * {@code POST {base}/v1beta/models/{model}:generateContent} with inline image parts plus a text
 * part and {@code generationConfig.responseMimeType=application/json} - the call the google-genai
 * SDK makes for Django's {@code _call_gemini}. Reuses the recommendation module's
 * {@link GeminiProperties} (same {@code GEMINI_API_KEY}, model, base URL). Built with the static
 * {@link RestClient#builder()} (no RestClient.Builder bean exists in Boot 4 - CLAUDE.md section 1).
 */
@Component
public class RestClientGeminiVisionClient implements GeminiVisionClient {

    private final String baseUrl;
    private final RestClient client;

    public RestClientGeminiVisionClient(GeminiProperties properties) {
        String url = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))
                .build();
    }

    @Override
    public String generateJson(String apiKey, String model, List<InlineImage> images, String prompt) {
        List<Object> parts = new ArrayList<>();
        for (InlineImage image : images) {
            Map<String, Object> inline = new LinkedHashMap<>();
            inline.put("mimeType", image.mimeType());
            inline.put("data", Base64.getEncoder().encodeToString(image.data()));
            parts.add(Map.of("inlineData", inline));
        }
        parts.add(Map.of("text", prompt));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", Map.of("responseMimeType", "application/json"));
        try {
            Map<?, ?> response = client.post()
                    .uri(baseUrl + "/v1beta/models/" + model + ":generateContent")
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return extractText(response);
        } catch (RestClientResponseException e) {
            throw new GeminiApiException(e.getStatusCode().value(), e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }

    /** SDK response.text: text parts of the first candidate concatenated (thought parts skipped). */
    static String extractText(Map<?, ?> response) {
        if (response == null || !(response.get("candidates") instanceof List<?> candidates) || candidates.isEmpty()) {
            return null;
        }
        if (!(candidates.get(0) instanceof Map<?, ?> candidate) || !(candidate.get("content") instanceof Map<?, ?> content)
                || !(content.get("parts") instanceof List<?> parts) || parts.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        boolean any = false;
        for (Object p : parts) {
            if (p instanceof Map<?, ?> part && part.get("text") instanceof String s
                    && !Boolean.TRUE.equals(part.get("thought"))) {
                text.append(s);
                any = true;
            }
        }
        return any ? text.toString() : null;
    }
}
