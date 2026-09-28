package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the one place ../../backend/dish/services/__init__.py raises
 * ninja's own {@code ValidationError} rather than a project exception:
 *
 * <pre>
 * raise ValidationError([{"loc": ["custom_name"],
 *                         "msg": "custom_name la bat buoc khi ingredient_uid = null"}])
 * </pre>
 *
 * <p>Django routes that through
 * {@code utils/router/exception.py::_validation_error_handler}, which — see
 * CLAUDE.md §6 — hardcodes <b>401</b> with {@code message_code =
 * "VALIDATION_ERROR"} and a {@code {field: [messages]}} detail. This class emits
 * exactly that envelope, matching what
 * {@code common.exception.GlobalExceptionHandler} produces for Spring's own bean
 * validation failures, so the FE sees one consistent shape.
 */
public class DishIngredientValidationException extends ApiException {

    public DishIngredientValidationException(String field, String message) {
        super(HttpStatus.UNAUTHORIZED, "VALIDATION_ERROR", "Validation error", detailOf(field, message));
    }

    private static Map<String, List<String>> detailOf(String field, String message) {
        Map<String, List<String>> detail = new LinkedHashMap<>();
        detail.put(field, List.of(message));
        return detail;
    }
}
