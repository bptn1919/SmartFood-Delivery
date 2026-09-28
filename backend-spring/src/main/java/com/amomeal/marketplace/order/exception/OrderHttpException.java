package com.amomeal.marketplace.order.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors django-ninja's generic {@code ninja.errors.HttpError(status, msg)} as
 * raised directly by ../../backend/order/api.py (the four
 * {@code HttpError(403, "You don't have permission to ... this order")} chef
 * ownership checks) and order/services/__init__.py
 * ({@code HttpError(400, "Some chefs are not verified for bank transfer...")}).
 * Django's {@code _default_http_error} renders these as
 * {@code message_code="HTTP_ERROR"}, {@code message="Http error"}, with the text
 * in {@code data} — reproduced exactly (same shape {@code profile}'s
 * {@code HttpBadRequestException} already uses; redeclared per-module like
 * {@code PermissionDeniedException} is, since order also needs the 403 variant).
 */
public class OrderHttpException extends ApiException {

    public OrderHttpException(HttpStatus status, String detailMessage) {
        super(status, "HTTP_ERROR", "Http error", detailMessage);
    }
}
