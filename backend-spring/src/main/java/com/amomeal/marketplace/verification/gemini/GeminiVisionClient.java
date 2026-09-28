package com.amomeal.marketplace.verification.gemini;

import java.util.List;

/**
 * Seam over Gemini's multimodal {@code models.generate_content} with
 * {@code response_mime_type="application/json"} as used by
 * ../../backend/verification/services/gemini.py. Production: {@link RestClientGeminiVisionClient};
 * tests substitute a fake (the real Gemini API is never called from tests). The recommendation
 * module's text-only {@code GeminiClient} cannot carry image parts, hence this separate seam.
 */
public interface GeminiVisionClient {

    /** One inline image part ({@code types.Blob(mime_type, data)}). */
    record InlineImage(byte[] data, String mimeType) {
    }

    /**
     * Sends {@code images} (in order) followed by {@code prompt} as one user turn and returns the
     * response text (the SDK's {@code response.text}); may be null.
     *
     * @throws GeminiApiException for an HTTP error status from Gemini (429 handling is the caller's)
     * @throws RuntimeException   on any other transport failure
     */
    String generateJson(String apiKey, String model, List<InlineImage> images, String prompt);

    /** google.genai.errors.ClientError equivalent: carries the HTTP status and body text. */
    class GeminiApiException extends RuntimeException {
        private final int status;

        public GeminiApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
