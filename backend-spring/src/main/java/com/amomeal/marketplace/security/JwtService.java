package com.amomeal.marketplace.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Mirrors ../backend/users/tokens.py's access-token half (HS256 JWT, claims
 * user_id/typ/iat/exp). This is the single JWT implementation reused by
 * every transport in the Django project (ninja HTTP, DRF chat, Channels
 * websocket) — keep it that way here too (CLAUDE.md §6), don't duplicate.
 */
@Service
public class JwtService {

    private final AuthProperties properties;
    private final SecretKey key;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(Long userId) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.accessTokenTtlMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .claim("user_id", userId)
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature, expiry and the {@code typ=access} claim.
     *
     * @throws InvalidOrExpiredTokenException on any signature/expiry/shape problem —
     *         mirrors AuthBear.authenticate's catch-all in ../backend/utils/router/authenticate.py.
     */
    public Long decodeAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception ex) {
            throw new InvalidOrExpiredTokenException();
        }
        if (!"access".equals(claims.get("typ", String.class))) {
            throw new InvalidOrExpiredTokenException();
        }
        Object userId = claims.get("user_id");
        if (userId == null) {
            throw new InvalidOrExpiredTokenException();
        }
        try {
            return Long.valueOf(userId.toString());
        } catch (NumberFormatException ex) {
            throw new InvalidOrExpiredTokenException();
        }
    }
}
