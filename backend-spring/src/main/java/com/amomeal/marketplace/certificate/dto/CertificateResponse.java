package com.amomeal.marketplace.certificate.dto;

import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateAttachment;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors certificate/schemas/responses.py::CertificateResponse (ModelSchema excluding
 * created_at/deleted/name_no_accent; FK fields serialize as the raw id).
 */
public record CertificateResponse(
        UUID uid, Instant updatedAt, String name, String description, String issuedBy,
        LocalDate issueDate, LocalDate expirationDate, Long owner, CertificateType certificateType,
        CertificateStatus status, Long verifiedBy, Instant verifiedAt, String rejectionReason,
        List<AttachmentDetail> attachments) {

    public record AttachmentDetail(String uid, String publicUrl, String originalName, int size,
                                   String contentType, int position) {
    }

    public static CertificateResponse of(Certificate c, List<CertificateAttachment> attachments) {
        return new CertificateResponse(c.getUid(), c.getUpdatedAt(), c.getName(), c.getDescription(),
                c.getIssuedBy(), c.getIssueDate(), c.getExpirationDate(),
                c.getOwner() == null ? null : c.getOwner().getId(), c.getCertificateType(), c.getStatus(),
                c.getVerifiedBy() == null ? null : c.getVerifiedBy().getId(), c.getVerifiedAt(),
                c.getRejectionReason(),
                attachments.stream().map(ca -> new AttachmentDetail(
                        ca.getAttachment().getUid().toString(), ca.getAttachment().getPublicUrl(),
                        ca.getAttachment().getOriginalName(),
                        ca.getAttachment().getSize() == null ? 0 : ca.getAttachment().getSize(),
                        ca.getAttachment().getContentType(), ca.getPosition())).toList());
    }
}
