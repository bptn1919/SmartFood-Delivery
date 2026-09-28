package com.amomeal.marketplace.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.auth.*} from application.yml — mirrors the AUTH_* env vars in Django settings.py. */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String jwtSecret,
        String jwtAlgorithm,
        long accessTokenTtlMinutes,
        long refreshTokenTtlMinutes,
        String refreshTokenPepper
) {
}
