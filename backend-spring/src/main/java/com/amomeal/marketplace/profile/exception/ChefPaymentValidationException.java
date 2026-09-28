package com.amomeal.marketplace.profile.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Mirrors the ninja {@code ValidationError} that Django's Pydantic
 * {@code field_validator}s on {@code ChefPaymentInfoRequest}
 * (../../backend/profile/schemas/chef_payment.py) raise at request-parsing
 * time, before the service layer ever runs. Django's
 * {@code utils/router/exception.py::_validation_error_handler} maps this to
 * {@code message_code="VALIDATION_ERROR"}, HTTP/error_code 401 (CLAUDE.md §6's
 * documented quirk — request validation failures are 401, not 400/422), with
 * {@code detail} shaped {@code {field: [messages]}}. {@link ChefPaymentService}
 * replicates the exact same normalize-then-regex-validate steps Django's
 * validators perform (strip spaces, collapse whitespace, etc.) and throws this
 * with the same detail shape so the wire contract matches.
 */
public class ChefPaymentValidationException extends ApiException {

    public ChefPaymentValidationException(String field, String message) {
        super(HttpStatus.UNAUTHORIZED, "VALIDATION_ERROR", "Validation error", Map.of(field, java.util.List.of(message)));
    }
}
