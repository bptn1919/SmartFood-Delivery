package com.amomeal.marketplace.certificate.repository;

import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateAttachment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CertificateAttachmentRepository extends JpaRepository<CertificateAttachment, Long> {

    @EntityGraph(attributePaths = "attachment")
    List<CertificateAttachment> findByCertificateOrderByPositionAsc(Certificate certificate);

    boolean existsByCertificateAndAttachmentUid(Certificate certificate, UUID attachmentUid);

    Optional<CertificateAttachment> findByCertificateAndAttachmentUid(Certificate certificate, UUID attachmentUid);

    @Query("select max(ca.position) from CertificateAttachment ca where ca.certificate = :certificate")
    Integer findMaxPosition(@Param("certificate") Certificate certificate);

    /** Immediate bulk delete (a derived deleteAll would let Hibernate run the re-inserts first). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CertificateAttachment ca where ca.certificate = :certificate")
    void deleteAllByCertificate(@Param("certificate") Certificate certificate);
}
