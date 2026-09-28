package com.amomeal.marketplace.recommendation.support;

import com.amomeal.marketplace.recommendation.service.GeminiClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * In-memory stand-in for Gemini (never call the real API from tests). A test installs a
 * {@code responder} (prompt → response text, or throw to simulate an API failure) and can inspect
 * every call that was made.
 */
public class FakeGeminiClient implements GeminiClient {

    public record Call(String apiKey, String model, String prompt, Duration timeout) {
    }

    private final List<Call> calls = new ArrayList<>();
    private volatile Function<String, String> responder = prompt -> {
        throw new IllegalStateException("no Gemini responder configured");
    };

    public synchronized void reset() {
        calls.clear();
        responder = prompt -> {
            throw new IllegalStateException("no Gemini responder configured");
        };
    }

    public void respondWith(Function<String, String> responder) {
        this.responder = responder;
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public String generateContent(String apiKey, String model, String prompt, Duration timeout) {
        synchronized (this) {
            calls.add(new Call(apiKey, model, prompt, timeout));
        }
        return responder.apply(prompt);
    }
}
