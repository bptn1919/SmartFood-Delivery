package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors ../../backend/exceptions/ingredient.py::NutritionValidationException.
 * PORT-NOTE: declared in exceptions/ingredient.py but actually raised by
 * ../../backend/dish/services/__init__.py (dish's nutrition-bounds validation
 * pipeline, not ported yet) — kept here since that's where Django declares it;
 * `dish`'s future port should throw this same class.
 */
public class NutritionValidationException extends ApiException {

    private final String field;
    private final double severity;

    public NutritionValidationException(String message, String field) {
        this(message, field, 1.0);
    }

    public NutritionValidationException(String message, String field, double severity) {
        super(HttpStatus.BAD_REQUEST, "NUTRITION_INVALID", message, detailOf(message, field, severity));
        this.field = field;
        this.severity = severity;
    }

    private static Map<String, Object> detailOf(String message, String field, double severity) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        detail.put("field", field);
        detail.put("severity", severity);
        return detail;
    }

    public String getField() {
        return field;
    }

    public double getSeverity() {
        return severity;
    }
}
