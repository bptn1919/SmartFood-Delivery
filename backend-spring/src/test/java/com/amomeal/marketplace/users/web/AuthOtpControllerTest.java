package com.amomeal.marketplace.users.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.OtpPurpose;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import com.amomeal.marketplace.users.service.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the OTP + profile-adjacent half of
 * {@code ../../backend/users/api.py::AuthenticateAPI} through the full stack
 * (real filter chain, real Argon2, real Postgres/Redis via Testcontainers).
 *
 * <p>The plaintext OTP is deliberately never returned by any endpoint — it only
 * goes out by email. Rather than parse a mail server, these tests mint the OTP
 * through the same {@link OtpService} the controller uses and then drive the
 * real HTTP endpoint with it; the controller/service/DB path under test is
 * unchanged, only the delivery channel is short-circuited.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthOtpControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private OtpService otpService;

    private static final String PASSWORD = "correct-horse-battery";

    private String uniqueEmail(String prefix) {
        return prefix + System.nanoTime() + "@amomeal.test";
    }

    private String signup(String email) throws Exception {
        String body = """
                {"firstname":"Mai","lastname":"Nguyen","email":"%s","password":"%s",
                 "password_confirm":"%s","phone_number":"0900000007"}
                """.formatted(email, PASSWORD, PASSWORD);
        return mockMvc.perform(post("/api/auth/signup").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String loginAccessToken(String email, String password) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractField(json, "access_token");
    }

    /** Mints a fresh OTP for a user the way the API does, returning {token, code}. */
    private String[] freshOtp(Long userId, OtpPurpose purpose, String targetEmail) {
        otpService.deactivateAllForUser(userId);
        OtpService.CreatedOtp created = otpService.create(userId, purpose, targetEmail);
        return new String[]{created.record().getResetSessionToken(), created.plainOtp()};
    }

    private static String wrongCode(String realCode) {
        return "0000".equals(realCode) ? "1111" : "0000";
    }

    // =====================================================================
    // signup -> verify-otp
    // =====================================================================

    @Test
    void signup_createsAnInactiveAccountThatCannotLogInUntilTheOtpIsVerified() throws Exception {
        String email = uniqueEmail("signup");
        String signupJson = signup(email);

        // OtpSessionResponse = {id, reset_session_token, purpose, target_email}
        assertThat(signupJson)
                .contains("\"reset_session_token\"")
                .contains("\"purpose\":\"SIGNUP\"")
                .doesNotContain("otp_hash");

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.isActive()).isFalse();
        // Django's Query.create_user derives the username from the email local part.
        assertThat(user.getUsername()).isEqualTo(email.split("@")[0]);
        assertThat(user.getFirstName()).isEqualTo("Mai");

        // Inactive -> 403 ACCOUNT_DEACTIVATED (login's third check, after email+password).
        mockMvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("ACCOUNT_DEACTIVATED")));

        // Signing up again with the same (still inactive) email reuses the row.
        signup(email);
        assertThat(customUserRepository.findByEmailIgnoreCase(email)).isPresent();

        String[] otp = freshOtp(user.getId(), OtpPurpose.SIGNUP, null);
        mockMvc.perform(post("/api/auth/verify-otp").contentType("application/json").content("""
                        {"reset_session_token":"%s","otp":"%s"}
                        """.formatted(otp[0], otp[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        CustomUser activated = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getRoles()).containsExactly(UserRole.CUSTOMER);

        // ...and now the account works, and an already-active email is a 409.
        assertThat(loginAccessToken(email, PASSWORD)).isNotBlank();
        mockMvc.perform(post("/api/auth/signup").contentType("application/json").content("""
                        {"firstname":"Mai","lastname":"Nguyen","email":"%s","password":"%s",
                         "password_confirm":"%s"}
                        """.formatted(email, PASSWORD, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("EMAIL_ALREADY_IN_USE")));
    }

    @Test
    void verifyOtp_wrongCodeThreeTimes_locksTheSessionOut() throws Exception {
        String email = uniqueEmail("lockout");
        signup(email);
        Long userId = customUserRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        String[] otp = freshOtp(userId, OtpPurpose.SIGNUP, null);
        String wrong = wrongCode(otp[1]);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/verify-otp").contentType("application/json").content("""
                            {"reset_session_token":"%s","otp":"%s"}
                            """.formatted(otp[0], wrong)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(containsString("INVALID_OTP")));
        }

        // 4th call: the cap is checked before the code, so the RIGHT code fails too.
        mockMvc.perform(post("/api/auth/verify-otp").contentType("application/json").content("""
                        {"reset_session_token":"%s","otp":"%s"}
                        """.formatted(otp[0], otp[1])))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Max OTP attempts exceeded")));

        // The record is now inactive, so the session token itself is dead: 401.
        mockMvc.perform(post("/api/auth/verify-otp").contentType("application/json").content("""
                        {"reset_session_token":"%s","otp":"%s"}
                        """.formatted(otp[0], otp[1])))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("INVALID_OR_EXPIRED_TOKEN")));

        // The account was never activated.
        assertThat(customUserRepository.findByEmailIgnoreCase(email).orElseThrow().isActive()).isFalse();
    }

    @Test
    void verifyOtp_unknownSessionToken_is401() throws Exception {
        mockMvc.perform(post("/api/auth/verify-otp").contentType("application/json").content("""
                        {"reset_session_token":"not-a-real-session","otp":"1234"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("INVALID_OR_EXPIRED_TOKEN")));
    }

    // =====================================================================
    // forgot password -> verify -> reset
    //
    // Django's own flow dead-ends here (verify-otp deactivates the record,
    // reset_password then requires it active) — fixed by explicit user decision,
    // see AuthService.resetPassword's javadoc and PROGRESS.md.
    // =====================================================================

    @Test
    void passwordReset_confirmMismatchIs400_thenTheVerifiedFlowActuallySucceeds() throws Exception {
        String email = uniqueEmail("reset");
        Long userId = registerActiveUser(email);

        mockMvc.perform(post("/api/auth/password/forget").contentType("application/json").content("""
                        {"email":"%s"}
                        """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"purpose\":\"RESET_PASSWORD\"")));

        // Unknown email -> 404 USER_NOT_FOUND
        mockMvc.perform(post("/api/auth/password/forget").contentType("application/json").content("""
                        {"email":"nobody-%d@amomeal.test"}
                        """.formatted(System.nanoTime())))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("USER_NOT_FOUND")));

        String[] otp = freshOtp(userId, OtpPurpose.RESET_PASSWORD, null);

        // Mismatched confirmation is rejected before the session is even looked up.
        mockMvc.perform(put("/api/auth/password/reset").contentType("application/json").content("""
                        {"reset_session_token":"%s","new_password":"aaaaaaaa","confirm_password":"bbbbbbbb"}
                        """.formatted(otp[0])))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("CONFIRM_PASSWORD_NOT_MATCH")));

        mockMvc.perform(post("/api/auth/verify-otp").contentType("application/json").content("""
                        {"reset_session_token":"%s","otp":"%s"}
                        """.formatted(otp[0], otp[1])))
                .andExpect(status().isOk());

        // Fixed flow: verify-otp deactivates the record, but reset_password no
        // longer requires active=True (see AuthService.resetPassword's javadoc) —
        // otpVerified + non-expired is enough, so this now actually succeeds.
        mockMvc.perform(put("/api/auth/password/reset").contentType("application/json").content("""
                        {"reset_session_token":"%s","new_password":"brand-new-pass","confirm_password":"brand-new-pass"}
                        """.formatted(otp[0])))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("true")));

        // ...the old password no longer works, the new one does.
        mockMvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(containsString("USERNAME_OR_PASSWORD_INCORRECT")));
        assertThat(loginAccessToken(email, "brand-new-pass")).isNotBlank();

        // The session token is single-use: replaying the same reset request now
        // fails because otpVerified/active state was consumed, not because of the
        // (removed) active=True filter.
        mockMvc.perform(put("/api/auth/password/reset").contentType("application/json").content("""
                        {"reset_session_token":"%s","new_password":"another-pass","confirm_password":"another-pass"}
                        """.formatted(otp[0])))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("INVALID_OR_EXPIRED_TOKEN")));
    }

    // =====================================================================
    // authenticated surface: /me, password/change, email-change, is-chef
    // =====================================================================

    @Test
    void me_returnsUserSchema_andUpdateMeWritesFullNameIntoFirstName() throws Exception {
        String email = uniqueEmail("me");
        registerActiveUser(email);
        String token = loginAccessToken(email, PASSWORD);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                // profile module isn't ported — the seam defaults to Django's own
                // "no CustomerProfile row" answer.
                .andExpect(jsonPath("$.data.is_onboarded").value(false));

        mockMvc.perform(put("/api/auth/me").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("""
                                {"full_name":"Nguyen Van A"}
                                """))
                .andExpect(status().isOk());

        // PORT-NOTE pinned: Django dumps the whole full_name into first_name.
        CustomUser updated = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(updated.getFirstName()).isEqualTo("Nguyen Van A");

        // auth=True in Django -> a bearer token is required.
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_rejectsAWrongOldPassword_thenSwapsTheCredential() throws Exception {
        String email = uniqueEmail("pwchange");
        registerActiveUser(email);
        String token = loginAccessToken(email, PASSWORD);

        mockMvc.perform(put("/api/auth/password/change").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("""
                                {"old_password":"not-the-password","new_password":"a-brand-new-one"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("PASSWORD_INCORRECT")));

        mockMvc.perform(put("/api/auth/password/change").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("""
                                {"old_password":"%s","new_password":"a-brand-new-one"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        assertThat(loginAccessToken(email, "a-brand-new-one")).isNotBlank();
        mockMvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void emailChange_requestThenVerify_movesTheAccountToTheNewAddress() throws Exception {
        String email = uniqueEmail("emailchange");
        Long userId = registerActiveUser(email);
        String token = loginAccessToken(email, PASSWORD);
        String newEmail = uniqueEmail("emailchange-new");

        String requestJson = mockMvc.perform(post("/api/auth/email-change/request")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("""
                                {"new_email":"%s"}
                                """.formatted(newEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(requestJson)
                .contains("\"purpose\":\"EMAIL_CHANGE\"")
                .contains("\"target_email\":\"" + newEmail + "\"");

        String[] otp = freshOtp(userId, OtpPurpose.EMAIL_CHANGE, newEmail);

        // Wrong code -> 403 INVALID_OTP (this path compares the hash directly; it
        // does not count attempts, exactly as Django's verify_email_change does not).
        mockMvc.perform(post("/api/auth/email-change/verify").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("""
                                {"reset_session_token":"%s","otp":"%s"}
                                """.formatted(otp[0], wrongCode(otp[1]))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("INVALID_OTP")));

        mockMvc.perform(post("/api/auth/email-change/verify").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("""
                                {"reset_session_token":"%s","otp":"%s"}
                                """.formatted(otp[0], otp[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        assertThat(customUserRepository.findById(userId).orElseThrow().getEmail()).isEqualTo(newEmail);
        assertThat(loginAccessToken(newEmail, PASSWORD)).isNotBlank();

        // Requesting a move onto an email another ACTIVE account holds -> 409.
        String takenEmail = uniqueEmail("taken");
        registerActiveUser(takenEmail);
        String movedToken = loginAccessToken(newEmail, PASSWORD);
        mockMvc.perform(post("/api/auth/email-change/request").header("Authorization", "Bearer " + movedToken)
                        .contentType("application/json").content("""
                                {"new_email":"%s"}
                                """.formatted(takenEmail)))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("EMAIL_ALREADY_IN_USE")));
    }

    @Test
    void isChef_reflectsTheChefRole_andChefIdIsAlwaysNullLikeDjango() throws Exception {
        String email = uniqueEmail("ischef");
        Long userId = registerActiveUser(email);
        String token = loginAccessToken(email, PASSWORD);

        mockMvc.perform(get("/api/auth/is-chef").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_chef").value(false));

        CustomUser user = customUserRepository.findById(userId).orElseThrow();
        user.addRole(UserRole.CHEF);
        customUserRepository.save(user);

        mockMvc.perform(get("/api/auth/is-chef").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_chef").value(true))
                // PORT-NOTE pinned: Django guards chef_id on hasattr(user, 'chef'), a
                // related_name no model declares, so it is always null.
                .andExpect(jsonPath("$.data.chef_id").doesNotExist());
    }

    // =====================================================================

    /** Bootstraps an ACTIVE account through /register (which assigns CUSTOMER). */
    private Long registerActiveUser(String email) throws Exception {
        String body = """
                {"username":"%s","email":"%s","password":"%s","phone_number":"0900000008"}
                """.formatted(email.split("@")[0], email, PASSWORD);
        mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk());
        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getRoles()).containsExactly(UserRole.CUSTOMER);
        return user.getId();
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
