package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::VerificationAlreadyCompleted. */
public class VerificationAlreadyCompletedException extends ApiException {
    public VerificationAlreadyCompletedException() {
        super(HttpStatus.CONFLICT, "VERIFICATION_ALREADY_COMPLETED",
                "Phiên xác minh đã hoàn tất. Không thể thực hiện lại.");
    }
}
