package com.amomeal.marketplace.verification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/verification/models.py::ChefVerificationSession. Cross-module references
 * (user, certificates, selfie attachment) are kept as plain ids/uids (real FKs in V15) so the
 * entity never lazy-loads outside a transaction.
 */
@Entity
@Table(name = "chef_verification_session")
@Getter
@Setter
@NoArgsConstructor
public class ChefVerificationSession {

    public static final int VERIFICATION_CODE_TTL_MINUTES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // ── CCCD ─────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "cccd_status", nullable = false, length = 20)
    private DocumentStepStatus cccdStatus = DocumentStepStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cccd_attachment_uids", nullable = false, columnDefinition = "jsonb")
    private List<String> cccdAttachmentUids = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cccd_extracted", columnDefinition = "jsonb")
    private Map<String, Object> cccdExtracted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cccd_confirmed", columnDefinition = "jsonb")
    private Map<String, Object> cccdConfirmed;

    // ── Business license ─────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "business_status", nullable = false, length = 20)
    private DocumentStepStatus businessStatus = DocumentStepStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_attachment_uids", nullable = false, columnDefinition = "jsonb")
    private List<String> businessAttachmentUids = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_extracted", columnDefinition = "jsonb")
    private Map<String, Object> businessExtracted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_confirmed", columnDefinition = "jsonb")
    private Map<String, Object> businessConfirmed;

    // ── Food safety ──────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "food_safety_status", nullable = false, length = 20)
    private DocumentStepStatus foodSafetyStatus = DocumentStepStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "food_safety_attachment_uids", nullable = false, columnDefinition = "jsonb")
    private List<String> foodSafetyAttachmentUids = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "food_safety_extracted", columnDefinition = "jsonb")
    private Map<String, Object> foodSafetyExtracted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "food_safety_confirmed", columnDefinition = "jsonb")
    private Map<String, Object> foodSafetyConfirmed;

    // ── Certificates created by finalize ─────────────────────────────────────
    @Column(name = "business_certificate_uid")
    private UUID businessCertificateUid;

    @Column(name = "food_safety_certificate_uid")
    private UUID foodSafetyCertificateUid;

    // ── Cross validation ─────────────────────────────────────────────────────
    @Column(name = "cross_validation_passed")
    private Boolean crossValidationPassed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cross_validation_errors", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> crossValidationErrors = new ArrayList<>();

    // ── Selfie ───────────────────────────────────────────────────────────────
    @Column(name = "verification_code", length = 20)
    private String verificationCode;

    @Column(name = "verification_code_expires_at")
    private Instant verificationCodeExpiresAt;

    @Column(name = "selfie_attachment_uid")
    private UUID selfieAttachmentUid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selfie_extracted", columnDefinition = "jsonb")
    private Map<String, Object> selfieExtracted;

    @Column(name = "face_similarity_score")
    private Double faceSimilarityScore;

    // ── Risk & decision ──────────────────────────────────────────────────────
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_flags", nullable = false, columnDefinition = "jsonb")
    private List<String> riskFlags = new ArrayList<>();

    @Column(name = "risk_score", nullable = false)
    private int riskScore = 0;

    @Column(length = 20)
    private String decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private VerificationSessionStatus status = VerificationSessionStatus.IN_PROGRESS;

    // ── Post-verification safe identity ──────────────────────────────────────
    @Column(name = "cccd_number_masked", length = 20)
    private String cccdNumberMasked;

    @Column(name = "cccd_number_hash", columnDefinition = "TEXT")
    private String cccdNumberHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verified_identity", columnDefinition = "jsonb")
    private Map<String, Object> verifiedIdentity;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean allDocumentsConfirmed() {
        return cccdStatus == DocumentStepStatus.CONFIRMED
                && businessStatus == DocumentStepStatus.CONFIRMED
                && foodSafetyStatus == DocumentStepStatus.CONFIRMED;
    }

    /** Django generate_verification_code: VERIFY- + 6 digits (100000..999999), TTL 10 min. */
    public String generateVerificationCode(Instant now) {
        String code = "VERIFY-" + (RANDOM.nextInt(900000) + 100000);
        this.verificationCode = code;
        this.verificationCodeExpiresAt = now.plus(Duration.ofMinutes(VERIFICATION_CODE_TTL_MINUTES));
        return code;
    }

    /** Django verification_code_is_valid: code present, expiry set and now &lt;= expiry. */
    public boolean verificationCodeIsValid(Instant now) {
        return verificationCode != null && !verificationCode.isEmpty()
                && verificationCodeExpiresAt != null && !now.isAfter(verificationCodeExpiresAt);
    }
}
