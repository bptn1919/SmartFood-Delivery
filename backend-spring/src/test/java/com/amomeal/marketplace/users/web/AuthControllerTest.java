package com.amomeal.marketplace.users.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * End-to-end verification of the auth foundation slice through the full
 * stack (real filter chain, real Argon2 hashing, real Postgres/Redis via
 * Testcontainers) — exercises the response envelope contract (CLAUDE.md §3),
 * the two distinct 401 flavors (missing vs. invalid token, CLAUDE.md §6),
 * and the register -> login -> refresh -> logout lifecycle.
 *
 * All JSON keys here are snake_case (message_code/access_token/...) — that's
 * the real Django/FE-admin contract (global {@code spring.jackson.property-
 * naming-strategy=SNAKE_CASE}, see application.yml). This test originally
 * asserted camelCase, which only "passed" because it matched this module's
 * own (wrong) output rather than FE-admin's actual expectations — caught
 * during the `dish` port by cross-checking FE-admin source, fixed here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerLoginRefreshLogoutLifecycle() throws Exception {
        String email = "chef+" + System.nanoTime() + "@amomeal.test";
        String registerBody = """
                {"username":"chef1","email":"%s","password":"correct-horse-battery","phone_number":"0900000000"}
                """.formatted(email);

        // register — declared @ResponseStatus(CREATED) but transport is forced to 200,
        // error_code carries 201 instead (CLAUDE.md §3).
        var registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(registerBody))
                .andReturn();
        assertThat(registerResult.getResponse().getStatus()).isEqualTo(200);
        String registerJson = registerResult.getResponse().getContentAsString();
        assertThat(registerJson)
                .contains("\"message_code\":\"SUCCESS\"")
                .contains("\"error_code\":201")
                .contains("\"access_token\"")
                .contains("\"refresh_token\"");

        // duplicate email -> 409, real transport status, EMAIL_ALREADY_IN_USE
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(registerBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("EMAIL_ALREADY_IN_USE")));

        // login with wrong password -> 406 USERNAME_OR_PASSWORD_INCORRECT
        String badLogin = """
                {"email":"%s","password":"wrong-password"}
                """.formatted(email);
        mockMvc.perform(post("/api/auth/login").contentType("application/json").content(badLogin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotAcceptable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("USERNAME_OR_PASSWORD_INCORRECT")));

        // login correctly
        String loginBody = """
                {"email":"%s","password":"correct-horse-battery"}
                """.formatted(email);
        String loginJson = mockMvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andReturn().getResponse().getContentAsString();
        String refreshToken = extractField(loginJson, "refresh_token");
        assertThat(refreshToken).isNotBlank();

        // refresh rotates the token — old one becomes invalid immediately after.
        String refreshBody = "{\"refresh_token\":\"" + refreshToken + "\"}";
        String refreshJson = mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotatedRefreshToken = extractField(refreshJson, "refresh_token");
        String accessToken = extractField(refreshJson, "access_token");
        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("INVALID_OR_EXPIRED_TOKEN")));

        // logout revokes the current refresh token. Django: PUT /api/auth/logout,
        // auth=True, body optional (FE-admin's authService.logout sends none).
        String logoutBody = "{\"refresh_token\":\"" + rotatedRefreshToken + "\"}";
        mockMvc.perform(put("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json").content(logoutBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(logoutBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());

        // ...and logout itself now requires a bearer token, as Django's auth=True does.
        mockMvc.perform(put("/api/auth/logout"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithoutTokenReturnsGenericUnauthorized() throws Exception {
        mockMvc.perform(get("/api/does-not-exist-yet"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"message_code\":\"UNAUTHORIZED\"")));
    }

    @Test
    void protectedEndpointWithInvalidTokenReturnsSpecificCode() throws Exception {
        mockMvc.perform(get("/api/does-not-exist-yet").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("INVALID_OR_EXPIRED_TOKEN")));
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
