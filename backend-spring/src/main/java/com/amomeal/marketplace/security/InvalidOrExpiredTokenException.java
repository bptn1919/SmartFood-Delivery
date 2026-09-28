package com.amomeal.marketplace.security;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/auth.py::InvalidOrExpiredToken. */
public class InvalidOrExpiredTokenException extends ApiException {
    public InvalidOrExpiredTokenException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_OR_EXPIRED_TOKEN", "Invalid or expired token");
    }
}
