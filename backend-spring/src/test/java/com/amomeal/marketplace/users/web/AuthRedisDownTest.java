package com.amomeal.marketplace.users.web;

import com.amomeal.marketplace.users.repository.AuthenticateTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Django backend-edit commit 1341f0c: refresh tokens are stored DB-first, so the whole auth
 * lifecycle keeps working while Redis is down. This context has a real Postgres but NO Redis
 * at all — {@code spring.data.redis} points at a closed local port, so every Redis call fails
 * (connection refused), exactly like an outage.
 */
@Import(AuthRedisDownTest.PostgresOnly.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthRedisDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"));
        }
    }

    private static final int DEAD_PORT = freePort();

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort(); // closed right away: nothing listens there afterwards
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> DEAD_PORT);
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
        registry.add("app.stock.sweep-enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redis;
    @Autowired AuthenticateTokenRepository tokenRepository;

    @Test
    void registerLoginRefreshLogout_allWork_whileRedisIsUnreachable() throws Exception {
        // Precondition: Redis really is down for this context.
        assertThatThrownBy(() -> redis.opsForValue().get("rt:probe")).isInstanceOf(RuntimeException.class);

        String email = "redisdown+" + System.nanoTime() + "@amomeal.test";
        String registerJson = mockMvc.perform(post("/api/auth/register").contentType("application/json").content("""
                        {"username":"rd1","email":"%s","password":"correct-horse-battery","phone_number":"0900000000"}
                        """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(registerJson).contains("\"error_code\":201").contains("\"refresh_token\"");

        String loginJson = mockMvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"%s","password":"correct-horse-battery"}
                        """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = field(loginJson, "refresh_token");
        assertThat(refreshToken).isNotBlank();
        // The durable copy is what made this possible.
        assertThat(tokenRepository.count()).isGreaterThanOrEqualTo(2);

        // refresh (served from the DB fallback) rotates: new token works, old one is revoked.
        String refreshBody = "{\"refresh_token\":\"" + refreshToken + "\"}";
        String refreshJson = mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotated = field(refreshJson, "refresh_token");
        String accessToken = field(refreshJson, "access_token");
        assertThat(rotated).isNotEqualTo(refreshToken);
        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(status().isUnauthorized());

        // authenticated call with the new access token (JWT only, no Redis involved)
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // logout revokes in the DB; the revoked token can no longer refresh.
        String logoutBody = "{\"refresh_token\":\"" + rotated + "\"}";
        mockMvc.perform(put("/api/auth/logout").header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json").content(logoutBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(logoutBody))
                .andExpect(status().isUnauthorized());

        // logout-all (no body) also works from the DB alone.
        String login2 = mockMvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"%s","password":"correct-horse-battery"}
                        """.formatted(email)))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(put("/api/auth/logout").header("Authorization", "Bearer " + field(login2, "access_token")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").contentType("application/json")
                        .content("{\"refresh_token\":\"" + field(login2, "refresh_token") + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
