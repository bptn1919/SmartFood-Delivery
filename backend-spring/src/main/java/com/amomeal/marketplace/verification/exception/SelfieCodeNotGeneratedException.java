package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::SelfieCodeNotGenerated (declared but never raised in Django). */
public class SelfieCodeNotGeneratedException extends ApiException {
    public SelfieCodeNotGeneratedException() {
        super(HttpStatus.BAD_REQUEST, "SELFIE_CODE_NOT_GENERATED",
                "Chưa có mã xác thực. Vui lòng gọi GET /verification/selfie/code trước.");
    }
}
