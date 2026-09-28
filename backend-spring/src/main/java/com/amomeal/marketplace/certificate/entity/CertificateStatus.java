package com.amomeal.marketplace.certificate.entity;

/**
 * Mirrors utils/enums.py::CertificateStatusEnum. Note there is NO "APPROVED" value
 * (see ChefCertificationProviderImpl for the Django bug that hinges on that).
 */
public enum CertificateStatus { PENDING, ACTIVE, EXPIRED, REVOKED }
