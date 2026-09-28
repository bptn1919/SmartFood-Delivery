package com.amomeal.marketplace.users.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/users.py::EmailAlreadyInUse. */
public class EmailAlreadyInUseException extends ApiException {
    public EmailAlreadyInUseException() {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_IN_USE", "Email already in use");
    }
}
