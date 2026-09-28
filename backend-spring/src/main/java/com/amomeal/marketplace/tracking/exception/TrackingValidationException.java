package com.amomeal.marketplace.tracking.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Django-ninja validation failure (missing lat/lng query params) - project-wide 401 VALIDATION_ERROR (CLAUDE.md 6). */
public class TrackingValidationException extends ApiException {
    public TrackingValidationException(String message) {
        super(HttpStatus.UNAUTHORIZED, "VALIDATION_ERROR", "Validation error", message);
    }
}
