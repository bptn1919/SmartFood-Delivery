package com.amomeal.marketplace.admin.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Django's request-validation failure (ninja {@code ValidationError}): HTTP 401 VALIDATION_ERROR with
 * {@code {field: [msgs]}}. Query params are parsed by hand in the admin controller so that this fires
 * BEFORE the admin check, exactly like ninja validates the request before {@code @require_admin} runs.
 */
public class AdminValidationException extends ApiException {
    public AdminValidationException(String field, String message) {
        super(HttpStatus.UNAUTHORIZED, "VALIDATION_ERROR", "Validation error", Map.of(field, List.of(message)));
    }
}
