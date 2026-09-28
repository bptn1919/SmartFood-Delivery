package com.amomeal.marketplace.recommendation.service;

import java.time.Duration;

/**
 * Seam over Google Gemini's {@code models.generate_content} as used by
 * ../../backend/recommendation/services/daily_nutrition.py (meal parser + recipe generator).
 * Production: {@link RestClientGeminiClient} (plain HTTP, no SDK). Tests substitute a fake —
 * the real Gemini API is never called from tests.
 */
public interface GeminiClient {

    /**
     * Sends {@code prompt} as a single user turn and returns the SDK's {@code response.text}
     * equivalent (concatenated text parts of the first candidate; may be null).
     *
     * @param timeout null = no read timeout (Django's parser call passes none)
     * @throws RuntimeException on any transport / HTTP / payload failure (Django callers catch
     *                          everything and fall back)
     */
    String generateContent(String apiKey, String model, String prompt, Duration timeout);
}
