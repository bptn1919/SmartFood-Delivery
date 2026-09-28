package com.amomeal.marketplace.users.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../backend/exceptions/auth.py::InvalidOtp. */
public class InvalidOtpException extends ApiException {

    public InvalidOtpException() {
        super(HttpStatus.FORBIDDEN, "INVALID_OTP", "Invalid OTP");
    }

    public InvalidOtpException(String message) {
        super(HttpStatus.FORBIDDEN, "INVALID_OTP", message);
    }
}
