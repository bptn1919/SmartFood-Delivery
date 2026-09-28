package com.amomeal.marketplace.review.service;

/**
 * Port of ../../backend/review/services/__init__.py::ReviewService._predict_review_label
 * — the PhoBERT AI-model call (../../backend/AI-model-prod).
 *
 * <p>An interface (like {@code order}'s {@code ShippingFeeEstimator}) purely so
 * tests can substitute a fake instead of reaching a real AI service; production
 * wiring is {@link RestClientAiModelClient}.
 *
 * <p><b>Contract: this method must NEVER throw.</b> Django's real behavior
 * (verified against the actual code, not assumed) is that {@code
 * _predict_review_label} catches every failure mode itself — network error/
 * timeout ({@code requests.RequestException}), non-2xx HTTP status
 * ({@code response.raise_for_status()}, also a {@code RequestException}),
 * non-JSON body ({@code ValueError} from {@code response.json()}), a JSON body
 * that isn't a dict, and an unparseable {@code weight} — and always falls back
 * to {@code (weight=0.0, issue=None)} instead of propagating. The review write
 * ALWAYS proceeds; nothing about AI-model unavailability ever fails the
 * request. {@code AIModelUnavailableException}/{@code AIModelInvalidResponseException}
 * exist in {@code exceptions/reviews.py} and are declared in {@code review/api.py}'s
 * OpenAPI {@code exceptions=(...)} tuple, but are dead code — grepped the whole
 * Django tree, neither is ever actually raised. Ported the same way: both
 * classes exist ({@code review.exception.AIModelUnavailableException}/
 * {@code AIModelInvalidResponseException}) but {@link com.amomeal.marketplace.review.service.ReviewService}
 * never throws them.
 */
public interface AiModelClient {

    /** @return a real prediction, or {@link AiPrediction#FALLBACK} on any failure — never throws. */
    AiPrediction predict(AiPredictionRequest request);
}
