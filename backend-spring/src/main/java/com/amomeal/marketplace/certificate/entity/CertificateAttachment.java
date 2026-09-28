package com.amomeal.marketplace.certificate.entity;

import com.amomeal.marketplace.attachment.entity.Attachment;
import jakarta.persistence.*;
import lombok.*;

/** Mirrors ../../backend/certificate/models.py::CertificateAttachment (plain model, auto-int PK). */
@Entity
@Table(name = "certificate_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "certificate_uid", nullable = false)
    private Certificate certificate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attachment_uid", nullable = false)
    private Attachment attachment;

    @Column(nullable = false)
    private int position;
}
