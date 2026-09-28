package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** Mirrors exceptions/verification.py::VerificationDocumentError; detail = list of {code, message}. */
public class VerificationDocumentException extends ApiException {
    public VerificationDocumentException(List<Map<String, Object>> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_ANALYSIS_ERROR", "Không thể phân tích tài liệu.", errors);
    }
}
