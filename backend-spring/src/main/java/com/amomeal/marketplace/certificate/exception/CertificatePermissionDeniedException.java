package com.amomeal.marketplace.certificate.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors utils/exceptions.py::PermissionDeniedError(message) - 403 PERMISSION_DENIED with a custom message. */
public class CertificatePermissionDeniedException extends ApiException {
    public CertificatePermissionDeniedException(String message) {
        super(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", message);
    }
}
