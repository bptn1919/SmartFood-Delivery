package com.amomeal.marketplace.users.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/users.py::PasswordIncorrect. */
public class PasswordIncorrectException extends ApiException {

    public PasswordIncorrectException() {
        super(HttpStatus.UNAUTHORIZED, "PASSWORD_INCORRECT", "Password incorrect");
    }

    public PasswordIncorrectException(String message) {
        super(HttpStatus.UNAUTHORIZED, "PASSWORD_INCORRECT", message);
    }
}
