package com.amomeal.marketplace.users.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/users.py::UsernameOrPasswordIncorrect (yes, 406 — not a typo, preserved as-is). */
public class UsernameOrPasswordIncorrectException extends ApiException {
    public UsernameOrPasswordIncorrectException() {
        super(HttpStatus.NOT_ACCEPTABLE, "USERNAME_OR_PASSWORD_INCORRECT", "Username or password incorrect");
    }
}
