package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::CrossValidationNotReady. */
public class CrossValidationNotReadyException extends ApiException {
    public CrossValidationNotReadyException() {
        super(HttpStatus.BAD_REQUEST, "CROSS_VALIDATION_NOT_READY",
                "Vui lòng hoàn tất và xác nhận cả 3 tài liệu trước khi kiểm tra chéo.");
    }
}
