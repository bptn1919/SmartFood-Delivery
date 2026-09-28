package com.amomeal.marketplace.review.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real HTTP call in {@link RestClientAiModelClient} against a tiny
 * embedded {@link HttpServer} (no external AI service needed) — proves the
 * never-throws fallback contract for every failure mode Django's
 * {@code _predict_review_label} handles (see that method + {@link AiModelClient}'s
 * javadoc): unreachable server, non-2xx status, non-JSON body, invalid
 * {@code weight}, invalid {@code issue} type, and the success path.
 */
class RestClientAiModelClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RestClientAiModelClient client(String baseUrl, int timeoutSeconds) {
        AiModelProperties properties = new AiModelProperties();
        properties.setBaseUrl(baseUrl);
        properties.setTimeoutSeconds(timeoutSeconds);
        return new RestClientAiModelClient(properties);
    }

    private AiPredictionRequest request(String comment) {
        return new AiPredictionRequest(UUID.randomUUID(), Instant.now(), Instant.now(), 4, comment, false,
                null, UUID.randomUUID(), UUID.randomUUID(), 1L);
    }

    private HttpServer startServer(String responseBody, int statusCode) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/predict", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        srv.start();
        return srv;
    }

    @Test
    void predict_serverUnreachable_fallsBackToZeroWeightNullIssue() {
        // Nothing listening on this port -> connection refused, same class of failure as
        // Django's requests.RequestException (connection error / timeout).
        RestClientAiModelClient aiClient = client("http://127.0.0.1:1", 2);
        AiPrediction result = aiClient.predict(request("too salty"));
        assertThat(result).isEqualTo(AiPrediction.FALLBACK);
    }

    @Test
    void predict_non2xxStatus_fallsBackToZeroWeightNullIssue() throws IOException {
        server = startServer("{\"weight\":0.9,\"issue\":\"SALTY\"}", 500);
        RestClientAiModelClient aiClient = client("http://127.0.0.1:" + server.getAddress().getPort(), 5);
        AiPrediction result = aiClient.predict(request("too salty"));
        assertThat(result).isEqualTo(AiPrediction.FALLBACK);
    }

    @Test
    void predict_nonJsonBody_fallsBackToZeroWeightNullIssue() throws IOException {
        server = startServer("not json at all", 200);
        RestClientAiModelClient aiClient = client("http://127.0.0.1:" + server.getAddress().getPort(), 5);
        AiPrediction result = aiClient.predict(request("too salty"));
        assertThat(result).isEqualTo(AiPrediction.FALLBACK);
    }

    @Test
    void predict_invalidWeightType_fallsBackToZeroWeightAndNullIssue() throws IOException {
        // Django: float(weight) raising (TypeError/ValueError) resets BOTH weight and issue.
        server = startServer("{\"weight\":\"not-a-number\",\"issue\":\"SALTY\"}", 200);
        RestClientAiModelClient aiClient = client("http://127.0.0.1:" + server.getAddress().getPort(), 5);
        AiPrediction result = aiClient.predict(request("too salty"));
        assertThat(result).isEqualTo(AiPrediction.FALLBACK);
    }

    @Test
    void predict_invalidIssueType_keepsWeightButNullsIssue() throws IOException {
        // Django: only `issue` is reset to None if it isn't a str/None; weight stays.
        server = startServer("{\"weight\":0.7,\"issue\":123}", 200);
        RestClientAiModelClient aiClient = client("http://127.0.0.1:" + server.getAddress().getPort(), 5);
        AiPrediction result = aiClient.predict(request("too salty"));
        assertThat(result.weight()).isEqualTo(0.7);
        assertThat(result.issue()).isNull();
    }

    @Test
    void predict_validResponse_returnsRealPrediction() throws IOException {
        server = startServer("{\"weight\":0.85,\"issue\":\"TOO_SPICY\"}", 200);
        RestClientAiModelClient aiClient = client("http://127.0.0.1:" + server.getAddress().getPort(), 5);
        AiPrediction result = aiClient.predict(request("way too spicy"));
        assertThat(result.weight()).isEqualTo(0.85);
        assertThat(result.issue()).isEqualTo("TOO_SPICY");
    }
}
