package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gemini REST implementation of {@link GeminiClient}:
 * {@code POST {base}/v1beta/models/{model}:generateContent} with the {@code x-goog-api-key}
 * header — the same endpoint the google-genai SDK (used by Django) calls for
 * {@code generate_content(model=..., contents=prompt)}. The response text is extracted like the
 * SDK's {@code response.text}: the text parts of the first candidate, concatenated (thought
 * parts skipped); null when there is no candidate/part.
 *
 * <p>Built with the static {@link RestClient#builder()} (no RestClient.Builder bean exists in this
 * Boot 4 setup — CLAUDE.md §1).
 */
@Component
public class RestClientGeminiClient implements GeminiClient {

    private final String baseUrl;

    public RestClientGeminiClient(GeminiProperties properties) {
        String url = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
    }

    @Override
    public String generateContent(String apiKey, String model, String prompt, Duration timeout) {
        HttpClient.Builder http = HttpClient.newBuilder();
        if (timeout != null) {
            http.connectTimeout(timeout);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        if (timeout != null) {
            factory.setReadTimeout(timeout);
        }
        RestClient client = RestClient.builder().requestFactory(factory).build();
        Map<String, Object> body = Map.of("contents", List.of(Map.of("role", "user",
                "parts", List.of(Map.of("text", prompt)))));
        Map<?, ?> response = client.post()
                .uri(baseUrl + "/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return extractText(response);
    }

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
