package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::DocumentNotCompleted (declared but never raised in Django). */
public class DocumentNotCompletedException extends ApiException {
    public DocumentNotCompletedException() {
        super(HttpStatus.BAD_REQUEST, "DOCUMENT_NOT_COMPLETED",
                "File chưa được upload hoàn tất. Vui lòng gọi PUT /attachments/{uid}/completed trước.");
    }
}
