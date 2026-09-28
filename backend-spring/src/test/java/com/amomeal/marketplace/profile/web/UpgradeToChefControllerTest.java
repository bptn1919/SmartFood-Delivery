package com.amomeal.marketplace.profile.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the CUSTOMER→CHEF upgrade flow
 * ({@code POST /api/auth/upgrade-to-chef}), the centerpiece this `profile`
 * port exists to complete — {@code AuthService.upgradeCustomerToChef} (the
 * `users` module's role-transition half) plus {@code ProfileService}/
 * {@code ChefPaymentService} (the profile-creation half), all in one
 * transaction. This is the project's designated cross-module integration
 * point: it proves a CUSTOMER-role token really becomes able to hit a
 * CHEF-only endpoint in `dish`/`menu` — modules this task never touches the
 * source of — ONLY after the upgrade flow completes, not before.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpgradeToChefControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;
    @Autowired
    private AttachmentRepository attachmentRepository;

    private record Account(String token, Long userId) {
    }

    private Account registerAsCustomer(String prefix) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000007"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        // /register already assigns CUSTOMER (see PROGRESS.md) but pin it
        // explicitly so this test doesn't depend on that being unchanged.
        user.getRoles().clear();
        user.addRole(UserRole.CUSTOMER);
        customUserRepository.save(user);
        return new Account(extractField(json, "access_token"), user.getId());
    }

    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("field " + field + " not found in: " + json);
        }
        start += needle.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private String upgradeToChefBody() {
        return """
                {
                  "chef_profile": {"bio": "Tôi nấu ăn ngon", "kitchen_city": "Hà Nội"},
                  "chef_payment": {
                    "bank_name": "Vietcombank",
                    "bank_code": "123456",
                    "bank_account_number": "12345678",
                    "bank_account_name": "NGUYEN VAN A"
                  }
                }
                """;
    }

    @Test
    void upgradeToChef_customerBecomesChef_andCanThenHitChefOnlyDishAndMenuEndpoints() throws Exception {
        Account account = registerAsCustomer("upgrade-happy");

        // Before the upgrade: a CHEF-only endpoint in `dish` must reject this token.
        mockMvc.perform(get("/api/dishes/mine").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/menus/mine").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/upgrade-to-chef")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType("application/json")
                        .content(upgradeToChefBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bio").value("Tôi nấu ăn ngon"))
                .andExpect(jsonPath("$.data.kitchen_city").value("Hà Nội"))
                .andExpect(jsonPath("$.data.bank.bank_account_number").value("****5678"));

        CustomUser reloaded = customUserRepository.findById(account.userId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.hasRole(UserRole.CHEF)).isTrue();
        org.assertj.core.api.Assertions.assertThat(reloaded.hasRole(UserRole.CUSTOMER)).isFalse();

        // After the upgrade: the SAME account (re-logging in isn't even needed —
        // JwtAuthenticationFilter re-reads roles from the DB on every request)
        // can now reach CHEF-only endpoints in `dish` and `menu`.
        mockMvc.perform(get("/api/dishes/mine").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/menus/mine").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isNotFound()); // MENU_DOES_NOT_EXIST -- CHEF role accepted, just no menus yet.

        // And can now also reach the CHEF-gated endpoints this module itself owns.
        mockMvc.perform(get("/api/chef-payment").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bank_account_number").value("****5678"));
    }

    @Test
    void upgradeToChef_alreadyChef_isRejectedWithoutDoubleApplying() throws Exception {
        Account account = registerAsCustomer("upgrade-twice");
        mockMvc.perform(post("/api/auth/upgrade-to-chef")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType("application/json")
                        .content(upgradeToChefBody()))
                .andExpect(status().isOk());

        // Second attempt: no longer a CUSTOMER -> Django's bare `raise Exception`
        // -> generic 500 CONTACT_ADMIN_FOR_SUPPORT (not a purpose-built 4xx).
        mockMvc.perform(post("/api/auth/upgrade-to-chef")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType("application/json")
                        .content(upgradeToChefBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
    }

    @Test
    void upgradeToChef_anonymous_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/upgrade-to-chef")
                        .contentType("application/json")
                        .content(upgradeToChefBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upgradeToChef_withAvatar_resolvesThroughAttachmentService() throws Exception {
        Account account = registerAsCustomer("upgrade-avatar");
        Attachment attachment = Attachment.builder()
                .type(AttachmentType.CHEF_AVATAR)
                .originalName("avatar.jpg")
                .hashedName("avatar-" + UUID.randomUUID() + ".jpg")
                .size(2048)
                .contentType("image/jpeg")
                .bucket("amomeal-test-bucket")
                .directory("chef_avatar")
                .publicUrl("https://cdn.example.test/chef_avatar/avatar.jpg")
                .isCompleted(true)
                .build();
        UUID avatarUid = attachmentRepository.save(attachment).getUid();

        String body = """
                {
                  "chef_profile": {"avatar": "%s"},
                  "chef_payment": {
                    "bank_name": "VCB", "bank_code": "123456",
                    "bank_account_number": "12345678", "bank_account_name": "NGUYEN VAN A"
                  }
                }
                """.formatted(avatarUid);

        mockMvc.perform(post("/api/auth/upgrade-to-chef")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value("https://cdn.example.test/chef_avatar/avatar.jpg"));
    }
}
