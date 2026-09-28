package com.amomeal.marketplace.security;

import com.amomeal.marketplace.users.entity.AuthenticateToken;
import com.amomeal.marketplace.users.repository.AuthenticateTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link RefreshTokenService#store} is DB-first since Django backend-edit commit 1341f0c
 * ({@code users/redis_tokens.py}): the DB write is fatal/durable, Redis only a best-effort cache.
 */
class RefreshTokenServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private AuthenticateTokenRepository repository;
    private RefreshTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        repository = mock(AuthenticateTokenRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new RefreshTokenService(redis, repository,
                new AuthProperties("secret", "HS256", 15, 60, "pepper"), JsonMapper.builder().build());
    }

    private static RedisConnectionFailureException redisDown() {
        return new RedisConnectionFailureException("Unable to connect to Redis");
    }

    @Test
    void issue_writesTheDbFirst_thenCachesInRedis() {
        service.issue(7L);

        InOrder order = inOrder(repository, values);
        order.verify(repository).saveAndFlush(any(AuthenticateToken.class));
        order.verify(values).set(startsWith("rt:token:"), anyString(), eq(Duration.ofSeconds(3600)));
        verify(sets).add(eq("rt:user:7"), anyString());
    }

    @Test
    void issue_dbFailureIsFatal_andRedisIsNeverWritten() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("db down"));

        assertThatThrownBy(() -> service.issue(7L)).isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(values, sets);
    }

    @Test
    void issue_redisFailureIsBestEffort_theTokenIsStillIssuedFromTheDb() {
        doThrow(redisDown()).when(values).set(anyString(), anyString(), any(Duration.class));

        RefreshTokenService.IssuedRefreshToken issued = service.issue(7L);

        assertThat(issued.rawToken()).isNotBlank();
        verify(repository).saveAndFlush(any(AuthenticateToken.class));
    }

    @Test
    void rotateAndRevoke_workFromTheDb_whenEveryRedisCallFails() {
        when(values.get(anyString())).thenThrow(redisDown());
        doThrow(redisDown()).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenThrow(redisDown());
        String raw = "raw-token";
        String hash = service.hash(raw);
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(AuthenticateToken.builder()
                .userId(7L).tokenHash(hash).jti(UUID.randomUUID()).expiresAt(Instant.now().plusSeconds(600)).build()));

        RefreshTokenService.RotationResult rotated = service.rotate(raw);

        assertThat(rotated.userId()).isEqualTo(7L);
        assertThat(rotated.rawRefreshToken()).isNotEqualTo(raw);
        verify(repository).saveAndFlush(any(AuthenticateToken.class));       // new token persisted
        verify(repository).revokeByTokenHash(eq(hash), any(Instant.class));   // old one revoked in the DB

        service.revoke(raw);
        verify(repository, times(2)).revokeByTokenHash(eq(hash), any(Instant.class));
    }

    @Test
    void requirePayload_unknownToken_isRejected_evenWithRedisDown() {
        when(values.get(anyString())).thenThrow(redisDown());
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requirePayload("nope")).isInstanceOf(InvalidOrExpiredTokenException.class);
    }
}
