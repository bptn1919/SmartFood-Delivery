package com.amomeal.marketplace.cart.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Post-port authorization fix (2026-09-25): thrown when a caller touches a cart item that is
 * not in their own cart (Django's {@code toggle_select} never checked). Same 403 / PERMISSION_DENIED
 * shape as {@code profile}'s/{@code menu}'s per-module copies.
 */
public class CartPermissionDeniedException extends ApiException {

    public CartPermissionDeniedException() {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You don't have permission to perform this action");
    }
}
