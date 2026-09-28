package com.amomeal.marketplace.users.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Mirrors {@code ../backend/users/models.py::UserOTP} field for field.
 *
 * <p>The plaintext OTP is never stored — only an Argon2id hash
 * ({@code otp_hash}); the salt is embedded in the encoded hash string, exactly
 * like Django's {@code argon2.PasswordHasher}. {@code reset_session_token} is
 * the opaque handle the client carries between "request OTP" and
 * "verify OTP"/"reset password".
 */
@Entity
@Table(name = "user_otp", indexes = {
        @Index(name = "idx_user_otp_user_active", columnList = "user_id, active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "text")
    private String otpHash;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean otpVerified = false;

    @Column(nullable = false, unique = true, length = 255)
    private String resetSessionToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OtpPurpose purpose;

    @Column(length = 254)
    private String targetEmail;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Django: {@code UserOTP.is_expired()} — {@code now() > created_at + OTP_EXPIRY_MINUTES[purpose]}. */
    public boolean isExpired() {
        return Instant.now().isAfter(createdAt.plus(purpose.expiryMinutes(), ChronoUnit.MINUTES));
    }
}
