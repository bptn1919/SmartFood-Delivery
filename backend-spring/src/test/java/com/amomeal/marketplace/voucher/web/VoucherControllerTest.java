package com.amomeal.marketplace.voucher.web;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the voucher module through the full stack (real filter chain,
 * real Postgres via the shared {@link TestcontainersConfiguration}), mirroring
 * ../../backend/voucher/api.py. Modeled on {@code cart.web.CartControllerTest}'s pattern:
 * register through the real auth endpoint, then grant the account its Django role (and, where
 * needed, {@code is_staff}) via the repository.
 *
 * <p>Confirms the two authorization findings from {@code VoucherService}'s class javadoc over
 * real HTTP: (1) NO endpoint is role-gated — a plain CUSTOMER can create/list/validate a
 * voucher, matching Django's {@code api.py} having no {@code @require_group} anywhere; (2)
 * update/delete's ownership check has the ADMIN-group-OR-{@code is_staff} bypass — tested BOTH
 * ways: a plain ADMIN succeeds, AND a CHEF with {@code is_staff=true} (no ADMIN group at all)
 * also succeeds.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class VoucherControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomUserRepository customUserRepository;

    private record Account(String token, Long userId) {
    }

    private Account register(String prefix, UserRole role) throws Exception {
        return register(prefix, role, false);
    }

    private Account register(String prefix, UserRole role, boolean isStaff) throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        String email = prefix + "+" + nonce + "@amomeal.test";
        String body = """
                {"username":"%s-%s","email":"%s","password":"correct-horse-battery","phone_number":"0900000006"}
                """.formatted(prefix, nonce, email);
        String json = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CustomUser user = customUserRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.getRoles().clear();
        user.addRole(role);
        user.setStaff(isStaff);
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

    private static String createVoucherBody(String code) {
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant end = Instant.now().plus(30, ChronoUnit.DAYS);
        return """
                {"code":"%s","name":"Sale","discount_type":"PERCENTAGE","discount_value":10,
                "min_order_amount":0,"start_date":"%s","end_date":"%s","usage_limit_per_user":1,"is_active":true}
                """.formatted(code, start, end);
    }

    // ===================================================================
    // create / list / get
    // ===================================================================

    @Test
    void createVoucher_thenListMine_andGetByUidAndCode() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        String code = "SALE" + System.nanoTime();

        String created = mockMvc.perform(post("/api/vouchers")
                        .header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.voucher_type").value("SHOP_VOUCHER"))
                .andExpect(jsonPath("$.data.usage_count").value(0))
                .andReturn().getResponse().getContentAsString();
        String uid = extractField(created, "uid");

        mockMvc.perform(get("/api/vouchers").header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value(code));

        mockMvc.perform(get("/api/vouchers/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code));

        mockMvc.perform(get("/api/vouchers/code/" + code).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").value(uid));
    }

    @Test
    void createVoucher_duplicateCode_isBadRequest() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        String code = "DUP" + System.nanoTime();
        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("VOUCHER_CODE_ALREADY_EXISTS"));
    }

    @Test
    void getVoucher_nonexistentUid_is404() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        mockMvc.perform(get("/api/vouchers/" + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("VOUCHER_NOT_FOUND"));
    }

    /**
     * Post-port fix (2026-09-25): {@code create_voucher}'s docstring says "Chef only" but Django never
     * enforced it. Now CHEF or ADMIN only; a CUSTOMER gets 403, anonymous 401.
     */
    @Test
    void createVoucher_customerForbidden_chefAndAdminAllowed_anonymousUnauthorized() throws Exception {
        Account customer = register("customer", UserRole.CUSTOMER);
        Account chef = register("chef", UserRole.CHEF);
        Account admin = register("admin", UserRole.ADMIN);
        String code = "CUSTVOUCHER" + System.nanoTime();

        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("VOUCHER_NOT_OWNED"));
        mockMvc.perform(post("/api/vouchers").contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code));
        String adminCode = "ADMVOUCHER" + System.nanoTime();
        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json").content(createVoucherBody(adminCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(adminCode));
    }

    // ===================================================================
    // update / delete -- ownership + the ADMIN-or-is_staff quirk
    // ===================================================================

    @Test
    void updateVoucher_nonOwner_isForbidden_ownerSucceeds() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        Account otherChef = register("other-chef", UserRole.CHEF);
        String code = "UPD" + System.nanoTime();
        String created = mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uid = extractField(created, "uid");

        mockMvc.perform(patch("/api/vouchers/" + uid).header("Authorization", "Bearer " + otherChef.token())
                        .contentType("application/json").content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message_code").value("VOUCHER_NOT_OWNED"));

        mockMvc.perform(patch("/api/vouchers/" + uid).header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content("{\"name\":\"Renamed by owner\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed by owner"));
    }

    @Test
    void updateVoucher_plainAdminGroupMember_bypassesOwnership() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        Account admin = register("admin", UserRole.ADMIN);
        String code = "ADM" + System.nanoTime();
        String created = mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uid = extractField(created, "uid");

        mockMvc.perform(patch("/api/vouchers/" + uid).header("Authorization", "Bearer " + admin.token())
                        .contentType("application/json").content("{\"name\":\"Renamed by admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed by admin"));
    }

    /**
     * The one place {@code is_staff} genuinely still matters for authorization in the whole
     * Django backend (PROGRESS.md "Part 1 findings"): a CHEF with NO ADMIN group membership,
     * but {@code is_staff=true}, must also bypass ownership here.
     */
    @Test
    void updateVoucher_chefWithIsStaffTrue_bypassesOwnership_evenWithoutAdminGroup() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        Account staffChef = register("staff-chef", UserRole.CHEF, true);
        String code = "STF" + System.nanoTime();
        String created = mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uid = extractField(created, "uid");

        mockMvc.perform(patch("/api/vouchers/" + uid).header("Authorization", "Bearer " + staffChef.token())
                        .contentType("application/json").content("{\"name\":\"Renamed by staff chef\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed by staff chef"));
    }

    @Test
    void deleteVoucher_ownerSucceeds_thenGetIs404() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        String code = "DEL" + System.nanoTime();
        String created = mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uid = extractField(created, "uid");

        mockMvc.perform(delete("/api/vouchers/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/vouchers/" + uid).header("Authorization", "Bearer " + chef.token()))
                .andExpect(status().isNotFound());
    }

    // ===================================================================
    // validate / chef listing
    // ===================================================================

    @Test
    void validateVoucher_validAndInvalidCases() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        Account customer = register("customer", UserRole.CUSTOMER);
        String code = "VAL" + System.nanoTime();
        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk());

        String validBody = """
                {"code":"%s","chef_id":%d,"order_amount":200000}
                """.formatted(code, chef.userId());
        mockMvc.perform(post("/api/vouchers/validate").header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json").content(validBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_valid").value(true))
                .andExpect(jsonPath("$.data.discount_amount").value(20000))
                .andExpect(jsonPath("$.data.final_amount").value(180000));

        String wrongChefBody = """
                {"code":"%s","chef_id":999999,"order_amount":200000}
                """.formatted(code);
        mockMvc.perform(post("/api/vouchers/validate").header("Authorization", "Bearer " + customer.token())
                        .contentType("application/json").content(wrongChefBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_valid").value(false))
                .andExpect(jsonPath("$.data.discount_amount").doesNotExist())
                .andExpect(jsonPath("$.data.message").value("Voucher này không áp dụng cho chef của đơn hàng"));
    }

    @Test
    void listVouchersByChef_defaultsToAvailableOnly() throws Exception {
        Account chef = register("chef", UserRole.CHEF);
        Account customer = register("customer", UserRole.CUSTOMER);
        String code = "CHF" + System.nanoTime();
        mockMvc.perform(post("/api/vouchers").header("Authorization", "Bearer " + chef.token())
                        .contentType("application/json").content(createVoucherBody(code)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/vouchers/chef/" + chef.userId())
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value(code));
    }
}
