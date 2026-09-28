package com.amomeal.marketplace.review.service;

/**
 * Mirrors the {@code (weight, issue)} tuple returned by
 * ../../backend/review/services/__init__.py::ReviewService._predict_review_label.
 */
public record AiPrediction(double weight, String issue) {

    /** Django's fallback on every failure branch: {@code return 0.0, None}. */
    public static final AiPrediction FALLBACK = new AiPrediction(0.0, null);
}
