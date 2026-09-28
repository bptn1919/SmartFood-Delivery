package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/dishes.py::DishPermissionDenied.
 *
 * <p><b>PORT-NOTE:</b> the {@code "Dish is not deleted"} message is Django's
 * actual runtime value — a duplicate {@code message = ...} assignment in that
 * class body shadows the intended {@code "You can only modify your own dishes"}.
 * Preserved verbatim per CLAUDE.md §0.1; the full explanation lives on
 * {@link DishIsNotDeletedException}.
 */
public class DishPermissionDeniedException extends ApiException {

    public DishPermissionDeniedException() {
        super(HttpStatus.FORBIDDEN, "DISH_PERMISSION_DENIED", "Dish is not deleted");
    }
}
