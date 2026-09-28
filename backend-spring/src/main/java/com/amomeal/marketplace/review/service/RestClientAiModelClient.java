package com.amomeal.marketplace.review.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 1:1 port of ../../backend/review/services/__init__.py::ReviewService._predict_review_label
 * using Spring's {@link RestClient} (task requirement) instead of Django's
 * {@code requests.post}. Same endpoint ({@code {base_url}/predict}), same
 * request payload shape, same timeout semantics (Django's single
 * {@code timeout=} sets both connect+read; mirrored here on the underlying JDK
 * {@link HttpClient}), same never-throws fallback contract — see
 * {@link AiModelClient}'s javadoc.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AiModelProperties.class)
public class RestClientAiModelClient implements AiModelClient {

    private final RestClient restClient;
    private final String endpoint;

    /**
     * PORT-NOTE: builds its own {@link RestClient} via the static
     * {@link RestClient#builder()} rather than injecting an autoconfigured
     * {@code RestClient.Builder} bean — this Boot 4 setup does not wire up
     * {@code RestClientAutoConfiguration} with just {@code spring-boot-starter-webmvc}
     * on the classpath (same class of "starter, not raw dependency" gotcha CLAUDE.md
     * §1 documents for Flyway; confirmed by a real
     * {@code NoSuchBeanDefinitionException: RestClient$Builder} hit while wiring this
     * class's test). {@link RestClient} itself needs no extra Maven dependency (it's
     * already in {@code spring-web}, pulled in transitively by the webmvc starter) —
     * only the *autoconfigured builder bean* is unavailable, so building one directly
     * is the smallest fix and needs no new dependency.
     */
    public RestClientAiModelClient(AiModelProperties properties) {
        Duration timeout = Duration.ofSeconds(Math.max(properties.getTimeoutSeconds(), 1));
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);

        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().stripTrailing();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.endpoint = baseUrl + "/predict";
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public AiPrediction predict(AiPredictionRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("uid", (request.reviewUid() == null ? new UUID(0, 0) : request.reviewUid()).toString());
        body.put("created_at", request.createdAt() == null ? null : request.createdAt().toString());
        body.put("updated_at", request.updatedAt() == null ? null : request.updatedAt().toString());
        body.put("rating", request.rating());
        body.put("comment", request.comment());
        body.put("deleted", request.deleted());
        body.put("attachment_uid", request.attachmentUid() == null ? "" : request.attachmentUid().toString());
        body.put("weight", 0);
        body.put("issue", null);
        body.put("dish_uid", request.dishUid() == null ? "" : request.dishUid().toString());
        body.put("order_uid", request.orderUid() == null ? "" : request.orderUid().toString());
        body.put("owner_id", request.ownerId());

        Map<?, ?> data;
        try {
            data = restClient.post().uri(endpoint).body(body).retrieve().toEntity(Map.class).getBody();
        } catch (RestClientException ex) {
            log.warn("AI model unavailable, fallback to default label. endpoint={} error={}", endpoint, ex.toString());
            return AiPrediction.FALLBACK;
        } catch (RuntimeException ex) {
            // Django's except ValueError (non-JSON response.json()) — widened the same way
            // AhamoveShippingFeeEstimator widens its catch: a malformed AI response must
            // never be able to fail a review write.
            log.warn("AI model returned non-JSON/unreadable response, fallback to default label. endpoint={} error={}",
                    endpoint, ex.toString());
            return AiPrediction.FALLBACK;
        }

        if (data == null) {
            log.warn("AI model returned invalid payload type=null, fallback to default label.");
            return AiPrediction.FALLBACK;
        }

        Object weightObj = data.get("weight");
        Object issueObj = data.get("issue");

        double weight;
        try {
            weight = weightObj == null ? 0.0 : Double.parseDouble(String.valueOf(weightObj));
        } catch (NumberFormatException ex) {
            log.warn("AI model returned invalid weight={}, fallback to default label.", weightObj);
            return AiPrediction.FALLBACK;
        }

        String issue = issueObj instanceof String s ? s : null;
        return new AiPrediction(weight, issue);
    }
}
