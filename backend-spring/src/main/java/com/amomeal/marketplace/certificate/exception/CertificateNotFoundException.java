package com.amomeal.marketplace.certificate.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/certificates.py::CertificateNotFoundException. */
public class CertificateNotFoundException extends ApiException {
    public CertificateNotFoundException() {
        super(HttpStatus.NOT_FOUND, "CERTIFICATE_NOT_FOUND", "Certificate not found");
    }
}
