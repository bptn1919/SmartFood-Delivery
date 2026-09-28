package com.amomeal.marketplace.certificate.dto;

import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import jakarta.validation.constraints.NotNull;

/** Mirrors SetCertificateStatusSchema. */
public record SetCertificateStatusRequest(@NotNull CertificateStatus status, String rejectionReason) {
}
