package com.amomeal.marketplace.common;

import com.amomeal.marketplace.TestcontainersConfiguration;
import com.amomeal.marketplace.security.JwtService;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regressions for mismatches found in the first end-to-end run of backend-spring with FE-admin. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FeAdminIntegrationFixesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired CustomUserRepository users;

    private CustomUser user(String prefix, UserRole role) {
        String nonce = String.valueOf(System.nanoTime());
        CustomUser u = CustomUser.builder().username(prefix + nonce).email(prefix + nonce + "@amomeal.test")
                .password("x").build();
        u.addRole(role);
        return users.save(u);
    }

    private ResultActions call(MockHttpServletRequestBuilder b, CustomUser who) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + jwt.issueAccessToken(who.getId())));
    }

    @Test
    void unknownRoute_is404_notGeneric500() throws Exception {
        CustomUser admin = user("e2ea", UserRole.ADMIN);
        call(get("/api/does-not-exist"), admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error_code").value(404));
    }

    @Test
    void wrongMethod_is405() throws Exception {
        CustomUser admin = user("e2eb", UserRole.ADMIN);
        call(delete("/api/admin/users"), admin).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message_code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void missingQueryParam_isValidationError_notServerError() throws Exception {
        CustomUser chef = user("e2ec", UserRole.CHEF);
        call(get("/api/dishes/search"), chef).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.q[0]").value("Field required"));
    }

    @Test
    void malformedJsonAndBadUuid_areValidationErrors() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"));
        CustomUser admin = user("e2ed", UserRole.ADMIN);
        call(get("/api/dishes/undefined"), admin).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message_code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.uid[0]").value("Invalid value"));
    }

    @Test
    void validationDetailKeys_areSnakeCase() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"u\",\"email\":\"a@b.co\",\"password\":\"12345678\","
                                + "\"phone_number\":\"1234567890123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.phone_number").isArray())
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist());
    }

    @Test
    void voucherUpdate_acceptsNaiveDatetimeLocal_likeFeAdminSends() throws Exception {
        CustomUser admin = user("e2ee", UserRole.ADMIN);
        String code = "E2E" + (System.nanoTime() % 1_000_000);
        String created = call(post("/api/admin/voucher").contentType("application/json").content("""
                {"code":"%s","name":"n","description":"","voucher_type":"PLATFORM_SUBTOTAL","discount_type":"PERCENTAGE",
                 "discount_value":10,"max_discount_amount":20000,"min_order_amount":0,
                 "start_date":"2026-09-01T00:00","end_date":"2026-12-31T23:59","usage_limit":100,
                 "usage_limit_per_user":1,"is_active":true}""".formatted(code)), admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uid = created.replaceAll(".*\"uid\":\"([^\"]+)\".*", "$1");

        call(patch("/api/vouchers/" + uid).contentType("application/json")
                .content("{\"name\":\"renamed\",\"start_date\":\"2026-09-02T08:30\",\"end_date\":\"2026-12-30T23:59\"}"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("renamed"))
                .andExpect(jsonPath("$.data.start_date").value("2026-09-02T08:30:00Z"));
        // offset / Z forms keep working
        call(patch("/api/vouchers/" + uid).contentType("application/json")
                .content("{\"start_date\":\"2026-09-03T00:00:00.000Z\"}"), admin)
                .andExpect(status().isOk());
    }
}
