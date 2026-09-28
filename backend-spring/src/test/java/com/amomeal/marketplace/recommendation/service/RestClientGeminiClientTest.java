package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Gemini REST client against an embedded JDK HttpServer (never the real API). */
class RestClientGeminiClientTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "";
    private volatile long delayMs = 0;
    private RestClientGeminiClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            key.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        GeminiProperties props = new GeminiProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        client = new RestClientGeminiClient(props);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void postsGenerateContent_withApiKeyHeader_andConcatenatesFirstCandidateTextParts() {
        response = """
                {"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"{\\"meals\\":"},{"text":"[]}"}]}},
                               {"content":{"parts":[{"text":"ignored"}]}}]}
                """;
        String text = client.generateContent("k-123", "gemini-2.5-flash-lite", "hello phở", null);
        assertThat(text).isEqualTo("{\"meals\":[]}");
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-2.5-flash-lite:generateContent");
        assertThat(key.get()).isEqualTo("k-123");
        assertThat(body.get()).contains("\"role\":\"user\"").contains("hello phở");
    }

    @Test
    void noCandidates_isNullText_httpErrorAndTimeoutThrow() {
        response = "{\"candidates\":[]}";
        assertThat(client.generateContent("k", "m", "p", null)).isNull();

        status = 500;
        response = "{\"error\":{}}";
        assertThatThrownBy(() -> client.generateContent("k", "m", "p", null)).isInstanceOf(RuntimeException.class);

        status = 200;
        response = "{\"candidates\":[]}";
        delayMs = 1500;
        assertThatThrownBy(() -> client.generateContent("k", "m", "p", Duration.ofMillis(300)))
                .isInstanceOf(RuntimeException.class);
    }
}
