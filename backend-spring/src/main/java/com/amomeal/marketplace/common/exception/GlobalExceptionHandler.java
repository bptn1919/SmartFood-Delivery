package com.amomeal.marketplace.common.exception;

import com.amomeal.marketplace.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors ../backend/utils/router/exception.py::get_handlers. Every handler
 * here returns the REAL http status as the transport status (unlike the
 * success path, which {@code ResponseEnvelopeAdvice} forces to 200) — see
 * CLAUDE.md §3-4.
 *
 * NOTE (CLAUDE.md §6): Django hardcodes error_code=401 for validation errors
 * — looks like a bug, but is preserved intentionally here for FE-admin
 * compatibility. Do not change to 400/422 without checking with the user.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApiException(ApiException ex) {
        log.error("API exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getHttpStatus().value(), ex.getMessageCode(), ex.getMessage(), ex.getDetail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, List<String>> detail = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            detail.computeIfAbsent(snakeCase(fe.getField()), k -> new ArrayList<>()).add(fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401, "VALIDATION_ERROR", "Validation error", detail));
    }

    /**
     * Bean-validation reports Java field names (availableDate, chefProfile.bankCode); Django/ninja reports the
     * JSON (snake_case) names, and the FE keys its per-field errors on those.
     */
    static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleHandlerValidation(HandlerMethodValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401, "VALIDATION_ERROR", "Validation error", ex.getMessage()));
    }

    /**
     * Found in the first FE-admin end-to-end run: an unknown route (or a static-resource miss) used to
     * fall through to the generic 500. Django answers those with a plain 404 (never reaching ninja), and a
     * 500 would also make the FE treat a wrong path as a server crash.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Object>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "NOT_FOUND", "Not found", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(405, "METHOD_NOT_ALLOWED", "Method not allowed", null));
    }

    /**
     * Missing/mistyped query param or header, malformed JSON body, bad UUID/number in the path: in Django these
     * are ninja ValidationErrors (error_code 401 per the preserved quirk, CLAUDE.md section 6), not a 500.
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequestShape(Exception ex) {
        Map<String, List<String>> detail = new LinkedHashMap<>();
        if (ex instanceof MissingServletRequestParameterException m) {
            detail.put(m.getParameterName(), List.of("Field required"));
        } else if (ex instanceof MethodArgumentTypeMismatchException t) {
            detail.put(t.getName(), List.of("Invalid value"));
        } else if (ex instanceof MissingRequestHeaderException h) {
            detail.put(h.getHeaderName(), List.of("Field required"));
        } else {
            detail.put("body", List.of("Invalid or malformed request body"));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401, "VALIDATION_ERROR", "Validation error", detail));
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Object>> handleAuth(Exception ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401, "UNAUTHORIZED", "Unauthorized", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "CONTACT_ADMIN_FOR_SUPPORT", "Contact admin for support", null));
    }
}
