package com.amomeal.marketplace.dish.dto;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::WarningSchema — one entry of
 * the {@code warnings} array produced by the nutrition analysis pipeline.
 *
 * <p>Some Django warning dicts carry extra keys the ninja schema drops
 * (the "outlier" warning adds a {@code fields} list); those extra keys are
 * likewise not exposed here, matching the serialized payload.
 */
public record WarningResponse(String type, double severity, String message, String field) {

    public static WarningResponse of(String type, double severity, String message) {
        return new WarningResponse(type, severity, message, null);
    }

    public static WarningResponse of(String type, double severity, String message, String field) {
        return new WarningResponse(type, severity, message, field);
    }
}
