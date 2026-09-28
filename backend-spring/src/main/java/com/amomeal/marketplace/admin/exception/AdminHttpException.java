package com.amomeal.marketplace.admin.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ninja's {@code HttpError(status, msg)} rendered by Django's {@code _default_http_error}. */
public class AdminHttpException extends ApiException {
    public AdminHttpException(HttpStatus status, String detailMessage) {
        super(status, "HTTP_ERROR", "Http error", detailMessage);
    }
}
