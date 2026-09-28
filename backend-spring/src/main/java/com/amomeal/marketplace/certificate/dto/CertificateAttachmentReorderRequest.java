package com.amomeal.marketplace.certificate.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Mirrors CertificateAttachmentReorderSchema. */
public record CertificateAttachmentReorderRequest(@NotNull UUID attachmentUid, @NotNull Integer position) {
}
