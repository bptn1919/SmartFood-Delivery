package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * An unreadable request body (malformed JSON, an enum value that does not exist, a wrong type) is ninja's
 * {@code ValidationError} in Django: HTTP 401 VALIDATION_ERROR. The shared {@code GlobalExceptionHandler}
 * has no handler for Spring's {@link HttpMessageNotReadableException}, so it would fall into its 500
 * catch-all. This advice is scoped to the admin controllers only (the shared handler is not edited) and
 * runs first.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AdminController.class)
public class AdminExceptionAdvice {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> unreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "VALIDATION_ERROR",
                "Validation error", Map.of("body", List.of("Invalid request body"))));
    }
}
