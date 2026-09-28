package com.amomeal.marketplace.users.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/auth.py::ConfirmPasswordNotMatch. */
public class ConfirmPasswordNotMatchException extends ApiException {

    public ConfirmPasswordNotMatchException() {
        super(HttpStatus.BAD_REQUEST, "CONFIRM_PASSWORD_NOT_MATCH", "Confirm password does not match");
    }

    public ConfirmPasswordNotMatchException(String message) {
        super(HttpStatus.BAD_REQUEST, "CONFIRM_PASSWORD_NOT_MATCH", message);
    }
}
