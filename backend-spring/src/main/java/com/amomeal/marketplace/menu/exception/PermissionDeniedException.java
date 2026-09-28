package com.amomeal.marketplace.menu.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/menus.py::PermissionDenied.
 *
 * <p>Also stands in for Django's generic, cross-module
 * {@code utils.exceptions.PermissionDeniedError} raised by the
 * {@code @require_object_permission}/{@code @require_permission} decorators
 * in {@code menu/api.py} — the same collapsing {@code DishService.assertCanModify}
 * already does for the equivalent decorator in the `dish` module (one module
 * exception for both the decorator-level and service-level denial paths;
 * both map to 403 in Django either way).
 */
public class PermissionDeniedException extends ApiException {

    public PermissionDeniedException() {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You do not have permission to perform this action");
    }
}
