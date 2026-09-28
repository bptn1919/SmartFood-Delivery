package com.amomeal.marketplace.users.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/users.py::AccountDeactivated. */
public class AccountDeactivatedException extends ApiException {
    public AccountDeactivatedException() {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_DEACTIVATED",
                "Your account has been deactivated. Please contact admin for support.");
    }
}
