package com.amomeal.marketplace.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class every module's business exception extends — mirrors Django's
 * {@code utils/router/exception.py::APIException}. Subclasses live in
 * {@code <module>/exception/} and set a fixed httpStatus/messageCode/message,
 * one class per Django exception in {@code ../backend/exceptions/<module>.py}.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String messageCode;
    private final transient Object detail;

    protected ApiException(HttpStatus httpStatus, String messageCode, String message) {
        this(httpStatus, messageCode, message, null);
    }

    protected ApiException(HttpStatus httpStatus, String messageCode, String message, Object detail) {
        super(message);
        this.httpStatus = httpStatus;
        this.messageCode = messageCode;
        this.detail = detail;
    }
}
