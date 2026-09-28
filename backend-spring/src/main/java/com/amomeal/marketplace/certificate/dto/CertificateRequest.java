package com.amomeal.marketplace.certificate.dto;

import com.amomeal.marketplace.certificate.entity.CertificateType;

import java.time.LocalDate;

/**
 * Mirrors certificate/schemas/requests.py::CertificateSchema. No HTTP endpoint accepts this in
 * Django (create/update routes were removed - certificates come from the verification flow);
 * it is the service-level input the future verification port calls.
 */
public record CertificateRequest(String name, String description, String issuedBy, LocalDate issueDate,
                                 LocalDate expirationDate, CertificateType certificateType) {
}
