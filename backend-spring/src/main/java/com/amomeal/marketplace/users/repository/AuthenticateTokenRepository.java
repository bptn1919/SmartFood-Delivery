package com.amomeal.marketplace.users.repository;

import com.amomeal.marketplace.users.entity.AuthenticateToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuthenticateTokenRepository extends JpaRepository<AuthenticateToken, Long> {

    Optional<AuthenticateToken> findByTokenHash(String tokenHash);

    List<AuthenticateToken> findByUserIdAndRevokedAtIsNull(Long userId);

    @Modifying
    @Query("update AuthenticateToken t set t.revokedAt = :now where t.tokenHash = :tokenHash and t.revokedAt is null")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying
    @Query("update AuthenticateToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
