package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::VerificationSessionNotFound. */
public class VerificationSessionNotFoundException extends ApiException {
    public VerificationSessionNotFoundException() {
        super(HttpStatus.NOT_FOUND, "VERIFICATION_SESSION_NOT_FOUND",
                "Không tìm thấy phiên xác minh. Vui lòng bắt đầu lại.");
    }
}
