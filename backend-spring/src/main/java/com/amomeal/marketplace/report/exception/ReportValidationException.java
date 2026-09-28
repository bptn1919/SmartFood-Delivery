package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Django: a pydantic validator ValueError on a ninja schema, which the project-wide handler turns into
 * 401 VALIDATION_ERROR (utils/router/exception.py hardcodes 401). Detail = {field: [message]}.
 */
public class ReportValidationException extends ApiException {
    public ReportValidationException(String field, String message) {
        super(HttpStatus.UNAUTHORIZED, "VALIDATION_ERROR", "Validation error", Map.of(field, List.of(message)));
    }
}
