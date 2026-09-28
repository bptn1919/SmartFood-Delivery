package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** Mirrors exceptions/verification.py::CrossValidationFailed; detail = [{code, message, documents}]. */
public class CrossValidationFailedException extends ApiException {
    public CrossValidationFailedException(List<Map<String, Object>> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CROSS_VALIDATION_FAILED",
                "Thông tin giữa các giấy tờ không khớp nhau.", errors);
    }
}
