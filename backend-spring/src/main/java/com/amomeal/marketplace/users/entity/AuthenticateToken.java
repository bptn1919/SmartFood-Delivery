package com.amomeal.marketplace.users.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * DB backup for Redis-backed refresh tokens — mirrors
 * ../backend/users/models.py::AuthenticateToken exactly (see
 * ../backend/users/redis_tokens.py for the hybrid store/load pattern this
 * backs). Redis is the fast path; this table is the fallback used when
 * Redis is unavailable/restarted, and the audit trail (revoked_at kept, not
 * deleted).
 */
@Entity
@Table(name = "auth_refresh_token", indexes = {
        @Index(name = "idx_auth_refresh_token_user_revoked", columnList = "user_id, revoked_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthenticateToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, unique = true)
    private UUID jti;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Transient
    public boolean isValid() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
