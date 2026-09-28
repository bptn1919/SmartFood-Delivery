package com.amomeal.marketplace.verification.repository;

import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChefVerificationSessionRepository extends JpaRepository<ChefVerificationSession, Long> {

    Optional<ChefVerificationSession> findByUserId(Long userId);

    Optional<ChefVerificationSession> findFirstByBusinessCertificateUid(UUID certificateUid);

    Optional<ChefVerificationSession> findFirstByFoodSafetyCertificateUid(UUID certificateUid);
}
