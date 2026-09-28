package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::SelfieCodeMismatch. */
public class SelfieCodeMismatchException extends ApiException {
    public SelfieCodeMismatchException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SELFIE_CODE_MISMATCH",
                "Mã xác thực trong ảnh selfie không khớp. Vui lòng chụp lại.");
    }
}
