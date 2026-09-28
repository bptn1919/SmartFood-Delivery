package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::DocumentNotReadyToConfirm. */
public class DocumentNotReadyToConfirmException extends ApiException {
    public DocumentNotReadyToConfirmException() {
        super(HttpStatus.BAD_REQUEST, "DOCUMENT_NOT_READY_TO_CONFIRM",
                "Tài liệu chưa được phân tích thành công hoặc đã được xác nhận rồi.");
    }
}
