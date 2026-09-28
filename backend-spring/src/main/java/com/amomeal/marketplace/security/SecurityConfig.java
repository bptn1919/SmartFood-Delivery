package com.amomeal.marketplace.security;

import com.amomeal.marketplace.common.response.ApiErrorResponseWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT security — replaces Django's ninja {@code AuthBear} +
 * DRF/Channels auth transports (CLAUDE.md §6: one token implementation,
 * reused everywhere). Public paths mirror what Django exempts from the
 * default {@code AuthBear} auth (auth endpoints, docs) plus this stack's own
 * ops endpoints.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiErrorResponseWriter errorResponseWriter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Argon2id, OWASP-recommended defaults maintained by Spring Security.
        // NOTE: fresh DB (CLAUDE.md §5/§9) — no need for Django-hash compatibility.
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Django's AuthenticateAPI is declared auth=None and opts individual
                        // endpoints back IN with auth=True (../backend/users/api.py), so the
                        // public set has to be enumerated rather than blanket-permitting
                        // /api/auth/** — /me, /logout, /password/change, /email-change/* and
                        // /is-chef are all authenticated in Django.
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/auth/login", "/api/auth/signup", "/api/auth/register",
                                "/api/auth/refresh", "/api/auth/password/forget",
                                "/api/auth/verify-otp").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.PUT,
                                "/api/auth/password/reset").permitAll()
                        .requestMatchers(
                                "/docs/**", "/openapi.json", "/openapi.json/**", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health",
                                "/api/payment/payos/webhook", "/api/payment/payos/return",
                                // attachment module (PORT-NOTE): local-storage substitute for a
                                // direct-to-S3 presigned PUT — self-authorized by the upload-token
                                // query param instead of a bearer token, mirroring how S3 presigned
                                // URLs work (see AttachmentStorageService javadoc). Serving the
                                // uploaded files back out (/media/**, WebConfig) is likewise public,
                                // matching Django's MEDIA_URL being served directly by the web server.
                                "/api/attachments/*/upload", "/media/**",
                                // Websocket handshake auth is done in its own interceptor
                                // (chat module — mirrors Django's JWTAuthMiddleware), not here.
                                "/ws/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                errorResponseWriter.write(response, 401, "UNAUTHORIZED", "Unauthorized"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                errorResponseWriter.write(response, 403, "FORBIDDEN", "Forbidden")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
