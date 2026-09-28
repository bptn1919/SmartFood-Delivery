package com.amomeal.marketplace.certificate.service;

import com.amomeal.marketplace.certificate.entity.CertificateStatus;
import com.amomeal.marketplace.certificate.entity.CertificateType;
import com.amomeal.marketplace.certificate.repository.CertificateRepository;
import com.amomeal.marketplace.profile.service.ChefCertificationProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real ChefCertificationProvider (closes profile's seam; @Primary over
 * NoCertificateChefCertificationProvider).
 *
 * <p>PORT-NOTE / Django bug (CLAUDE.md 0.6, fixed by default): Django's
 * is_food_safety_certified filters status="APPROVED" (profile/schemas/responses.py,
 * tracking/location_service.py) - and the ORM annotation even uses certificate_type="SAFETY_TYPE" -
 * but CertificateStatusEnum has no APPROVED value and CertificateTypeEnum no SAFETY_TYPE, so
 * the flag is ALWAYS false in Django even after an admin approves (ACTIVE) a food-safety certificate. Default
 * here = the working behavior (FOOD_SAFETY + ACTIVE). app.certificate.preserve-approved-status-bug=true
 * (env CERTIFICATE_PRESERVE_APPROVED_STATUS_BUG) reproduces Django (always false). As in Django, neither
 * deleted nor expiration_date is consulted.
 */
@Primary
@Component
public class ChefCertificationProviderImpl implements ChefCertificationProvider {

    private final CertificateRepository certificateRepository;
    private final boolean preserveApprovedStatusBug;

    public ChefCertificationProviderImpl(CertificateRepository certificateRepository,
            @Value("${app.certificate.preserve-approved-status-bug:false}") boolean preserveApprovedStatusBug) {
        this.certificateRepository = certificateRepository;
        this.preserveApprovedStatusBug = preserveApprovedStatusBug;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFoodSafetyCertified(Long chefUserId) {
        if (preserveApprovedStatusBug || chefUserId == null) {
            return false;
        }
        return certificateRepository.existsByOwnerIdAndCertificateTypeAndStatus(
                chefUserId, CertificateType.FOOD_SAFETY, CertificateStatus.ACTIVE);
    }
}
