package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.review.service.AiModelProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Django {@code _call_external_llm_parser}'s HTTP call: {@code POST {AI_MODEL_BASE_URL}/nutrition/parse-meals}
 * with {@code {"text": ...}} and timeout {@code AI_MODEL_TIMEOUT_SECONDS} (reuses review's
 * {@link AiModelProperties} — same two settings). Returns the decoded JSON body ({} for an empty
 * body) or throws; the caller maps any failure to "no meals".
 *
 * <p>PORT-NOTE: that route does not exist in the only AI-service source in the repo
 * (AI-model-prod exposes /health and /predict) — RECOMMENDATION_FLOW.md §15. Ported as-is; in
 * practice it fails and the heuristic parser runs.
 */
@Component
public class ExternalMealParserClient {

    private final String endpoint;
    private final RestClient restClient;

    public ExternalMealParserClient(AiModelProperties properties) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base.isEmpty() ? null : base + "/nutrition/parse-meals";
        int seconds = properties.getTimeoutSeconds() == 0 ? 10 : properties.getTimeoutSeconds();
        Duration timeout = Duration.ofSeconds(Math.max(seconds, 1));
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** null endpoint = AI_MODEL_BASE_URL blank → Django returns [] before calling. */
    public boolean isConfigured() {
        return endpoint != null;
    }

    public Object parseMeals(String text) {
        String raw = restClient.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text == null ? "" : text))
                .retrieve()
                .body(String.class);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        return DailyNutritionMath.JSON.readValue(raw, Object.class);
    }
}
