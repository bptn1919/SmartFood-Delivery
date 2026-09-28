package com.amomeal.marketplace.security;

import com.amomeal.marketplace.users.entity.AuthenticateToken;
import com.amomeal.marketplace.users.repository.AuthenticateTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Hybrid refresh-token store (Postgres durable + Redis fast path) — mirrors
 * ../backend/users/redis_tokens.py (as of backend-edit commit 1341f0c: DB-first
 * {@code store}) + the refresh-token half of ../backend/users/tokens.py exactly,
 * including the fallback/restore behavior and which failures are fatal vs.
 * best-effort. With Redis down: issue/login/refresh/rotate/revoke/logout all
 * work from the DB (every Redis call is caught and logged); only the DB is fatal.
 *
 * Key layout (matches the Python implementation 1:1, just without the
 * Django-specific Redis-DB-number partitioning — see CLAUDE.md §6):
 *   rt:token:{HMAC-SHA256}  -> JSON {"user_id": long, "jti": uuid}   TTL = lifetime
 *   rt:user:{user_id}       -> SET of token hashes (for revoke-all)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String TOKEN_PREFIX = "rt:token:";
    private static final String USER_PREFIX = "rt:user:";

    private final StringRedisTemplate redisTemplate;
    private final AuthenticateTokenRepository authenticateTokenRepository;
    private final AuthProperties properties;
    private final ObjectMapper objectMapper;

    public record IssuedRefreshToken(String rawToken, String jti) {}

    public record RefreshTokenPayload(Long userId, String jti, String tokenHash) {}

    public record RotationResult(String rawRefreshToken, Long userId) {}

    public String hash(String rawToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.refreshTokenPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to hash refresh token", e);
        }
    }

    @Transactional
    public IssuedRefreshToken issue(Long userId) {
        String rawToken = generateRawToken();
        String tokenHash = hash(rawToken);
        String jti = UUID.randomUUID().toString();
        long ttlSeconds = properties.refreshTokenTtlMinutes() * 60;
        store(tokenHash, userId, jti, ttlSeconds);
        return new IssuedRefreshToken(rawToken, jti);
    }

    /** @throws InvalidOrExpiredTokenException if unknown/expired/revoked — mirrors get_refresh_payload. */
    public RefreshTokenPayload requirePayload(String rawToken) {
        return load(rawToken).orElseThrow(InvalidOrExpiredTokenException::new);
    }

    /** Rotates a refresh token: issue a new one first, then invalidate the old one (safe on partial failure). */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshTokenPayload payload = requirePayload(rawToken);
        IssuedRefreshToken next = issue(payload.userId());
        delete(payload.tokenHash(), payload.userId());
        return new RotationResult(next.rawToken(), payload.userId());
    }

    @Transactional
    public void revoke(String rawToken) {
        String tokenHash = hash(rawToken);
        load(rawToken).ifPresent(payload -> delete(tokenHash, payload.userId()));
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        int count = 0;
        try {
            Set<String> hashes = redisTemplate.opsForSet().members(userKey(userId));
            if (hashes != null && !hashes.isEmpty()) {
                count = hashes.size();
                redisTemplate.delete(hashes.stream().map(this::tokenKey).toList());
                redisTemplate.delete(userKey(userId));
            }
        } catch (Exception ex) {
            log.warn("Redis delete_all failed: {}", ex.getMessage());
        }
        try {
            int dbCount = authenticateTokenRepository.revokeAllForUser(userId, Instant.now());
            count = Math.max(count, dbCount);
        } catch (Exception ex) {
            log.warn("DB revoke_all failed: {}", ex.getMessage());
        }
        return count;
    }

    // ── internals ──────────────────────────────────────────────────────────

    private String generateRawToken() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Django {@code redis_tokens.store} since backend-edit commit 1341f0c: <b>DB first</b>. The DB
     * row is the durable copy, so login/refresh keep working while Redis is unreachable; Redis is
     * only a best-effort fast path that {@link #load} restores from the DB on a miss.
     */
    private void store(String tokenHash, Long userId, String jti, long ttlSeconds) {
        // 1. DB (durable) — failure IS fatal. saveAndFlush so a DB error surfaces here, like
        // Django's immediate objects.create(), not later at commit.
        try {
            authenticateTokenRepository.saveAndFlush(AuthenticateToken.builder()
                    .userId(userId)
                    .tokenHash(tokenHash)
                    .jti(UUID.fromString(jti))
                    .expiresAt(Instant.now().plusSeconds(ttlSeconds))
                    .build());
        } catch (RuntimeException ex) {
            log.error("DB refresh token store failed: {}", ex.getMessage());
            throw ex;
        }

        // 2. Redis (best-effort fast path).
        try {
            redisTemplate.opsForValue().set(tokenKey(tokenHash), toPayload(userId, jti), Duration.ofSeconds(ttlSeconds));
            redisTemplate.opsForSet().add(userKey(userId), tokenHash);
        } catch (Exception ex) {
            log.warn("Redis store failed; token remains available from DB: {}", ex.getMessage());
        }
    }

    private Optional<RefreshTokenPayload> load(String rawToken) {
        String tokenHash = hash(rawToken);

        try {
            String raw = redisTemplate.opsForValue().get(tokenKey(tokenHash));
            if (raw != null) {
                return Optional.of(fromPayload(raw, tokenHash));
            }
        } catch (Exception ex) {
            log.warn("Redis load failed, falling back to DB: {}", ex.getMessage());
        }

        return authenticateTokenRepository.findByTokenHash(tokenHash)
                .filter(AuthenticateToken::isValid)
                .map(record -> {
                    long ttl = Duration.between(Instant.now(), record.getExpiresAt()).getSeconds();
                    if (ttl > 0) {
                        try {
                            redisTemplate.opsForValue().set(
                                    tokenKey(tokenHash), toPayload(record.getUserId(), record.getJti().toString()), Duration.ofSeconds(ttl));
                            redisTemplate.opsForSet().add(userKey(record.getUserId()), tokenHash);
                        } catch (Exception ignored) {
                            // restore is best-effort
                        }
                    }
                    return new RefreshTokenPayload(record.getUserId(), record.getJti().toString(), tokenHash);
                });
    }

    private void delete(String tokenHash, Long userId) {
        try {
            redisTemplate.delete(tokenKey(tokenHash));
            redisTemplate.opsForSet().remove(userKey(userId), tokenHash);
        } catch (Exception ex) {
            log.warn("Redis delete failed: {}", ex.getMessage());
        }
        try {
            authenticateTokenRepository.revokeByTokenHash(tokenHash, Instant.now());
        } catch (Exception ex) {
            log.warn("DB revoke failed: {}", ex.getMessage());
        }
    }

    private String tokenKey(String tokenHash) {
        return TOKEN_PREFIX + tokenHash;
    }

    private String userKey(Long userId) {
        return USER_PREFIX + userId;
    }

    private String toPayload(Long userId, String jti) {
        // Jackson 3's ObjectMapper#writeValueAsString throws the unchecked JacksonException —
        // no try/catch needed (unlike Jackson 2's checked JsonProcessingException).
        return objectMapper.writeValueAsString(Map.of("user_id", userId, "jti", jti));
    }

    private RefreshTokenPayload fromPayload(String raw, String tokenHash) {
        Map<String, Object> map = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        return new RefreshTokenPayload(Long.valueOf(map.get("user_id").toString()), map.get("jti").toString(), tokenHash);
    }
}
