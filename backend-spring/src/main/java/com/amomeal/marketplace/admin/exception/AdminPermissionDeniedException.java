package com.amomeal.marketplace.admin.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors utils/exceptions.py::PermissionDeniedError(message) - 403 PERMISSION_DENIED. Django's
 * {@code require_admin} raises this (NOT a 401), and so does {@code get_order_detail} for a
 * missing order ("Order not found") and the verification review for a missing session.
 */
public class AdminPermissionDeniedException extends ApiException {
    public AdminPermissionDeniedException(String message) {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", message);
    }
}
