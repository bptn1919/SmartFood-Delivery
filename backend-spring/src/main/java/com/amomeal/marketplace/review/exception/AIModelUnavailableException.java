package com.amomeal.marketplace.review.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/reviews.py::AIModelUnavailableException.
 *
 * <p><b>PORT-NOTE — dead code, never actually raised.</b> This class is
 * declared in {@code review/api.py}'s {@code exceptions=(...)} tuple (for
 * ninja's OpenAPI schema generation only) on {@code create_review}/
 * {@code update_review}, but {@code ReviewService._predict_review_label}
 * NEVER raises it: every failure branch (network error, timeout, non-JSON
 * body, non-dict payload, invalid {@code weight}) is caught and silently
 * falls back to {@code (weight=0.0, issue=None)} — the review write always
 * proceeds. See {@link com.amomeal.marketplace.review.service.AiModelClient}'s
 * javadoc. Ported for completeness (§4) but unreachable, same as
 * {@code DishNotOrderedException}. Also subject to the file-wide
 * {@code status_code}-vs-{@code error_code} quirk (would be 500 regardless of
 * its cosmetic {@code SERVICE_UNAVAILABLE} in Django).
 */
public class AIModelUnavailableException extends ApiException {
    public AIModelUnavailableException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "AI_MODEL_UNAVAILABLE", "AI model service is unavailable");
    }
}
