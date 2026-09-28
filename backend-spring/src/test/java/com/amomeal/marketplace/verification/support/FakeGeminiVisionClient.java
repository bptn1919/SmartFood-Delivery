package com.amomeal.marketplace.verification.support;

import com.amomeal.marketplace.verification.gemini.GeminiVisionClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** In-memory Gemini stand-in: a responder maps the prompt to a JSON string (or throws). */
public class FakeGeminiVisionClient implements GeminiVisionClient {

    public record Call(String apiKey, String model, int imageCount, String prompt) {
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
    public String generateJson(String apiKey, String model, List<InlineImage> images, String prompt) {
        synchronized (this) {
            calls.add(new Call(apiKey, model, images.size(), prompt));
        }
        return responder.apply(prompt);
    }
}
