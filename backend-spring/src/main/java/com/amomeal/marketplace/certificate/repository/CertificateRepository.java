package com.amomeal.marketplace.certificate.repository;

import com.amomeal.marketplace.certificate.entity.Certificate;
import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface CertificateRepository extends JpaRepository<Certificate, UUID>, JpaSpecificationExecutor<Certificate> {

    Optional<Certificate> findByUidAndDeletedFalse(UUID uid);

    /**
     * Mirrors Django's Certificate.objects.filter(owner=..., certificate_type=..., status=...).exists()
     * - deliberately NOT filtering deleted (Django's profile/tracking subqueries don't).
     */
    boolean existsByOwnerIdAndCertificateTypeAndStatus(Long ownerId, CertificateType type, CertificateStatus status);
}
