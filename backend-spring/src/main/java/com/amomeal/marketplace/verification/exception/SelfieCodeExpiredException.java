package com.amomeal.marketplace.verification.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/verification.py::SelfieCodeExpired (also raised when no code was ever generated). */
public class SelfieCodeExpiredException extends ApiException {
    public SelfieCodeExpiredException() {
        super(HttpStatus.BAD_REQUEST, "SELFIE_CODE_EXPIRED",
                "Mã xác thực đã hết hạn. Vui lòng yêu cầu mã mới.");
    }
}
