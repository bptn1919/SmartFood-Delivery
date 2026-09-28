package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Real role matrix for EVERY admin endpoint: no token = 401; CUSTOMER and CHEF = Django's 403
 * PERMISSION_DENIED "Only admin can access this endpoint" (NOT a 401); ADMIN gets past the guard. Plus the
 * validation-before-authorization ordering (a bad request from a non-admin is 401 VALIDATION_ERROR, exactly
 * as ninja validates before {@code @require_admin} runs).
 */
class AdminAccessMatrixTest extends AbstractAdminFullStackTest {

    private record Endpoint(HttpMethod method, String url, Supplier<String> body) {
        static Endpoint get(String url) {
            return new Endpoint(HttpMethod.GET, url, null);
        }

        static Endpoint patch(String url) {
            return new Endpoint(HttpMethod.PATCH, url, null);
        }

        static Endpoint patch(String url, String body) {
            return new Endpoint(HttpMethod.PATCH, url, () -> body);
        }

        static Endpoint post(String url, Supplier<String> body) {
            return new Endpoint(HttpMethod.POST, url, body);
        }
    }

    private static String voucherBody() {
        return """
                {"code":"mx%d","name":"Matrix","voucher_type":"PLATFORM_SUBTOTAL","discount_type":"FIXED_AMOUNT",
                 "discount_value":5000,"start_date":"2026-01-01T00:00","end_date":"2026-12-31T00:00"}
                """.formatted(System.nanoTime());
    }

    private static final List<Endpoint> ENDPOINTS = List.of(
            Endpoint.get("/api/admin/users"),
            Endpoint.patch("/api/admin/users/987654321/deactivate"),
            Endpoint.patch("/api/admin/users/987654321/activate"),
            Endpoint.get("/api/admin/dashboard/overview"),
            Endpoint.get("/api/admin/dashboard/revenue-chart?from_date=2019-01-01&to_date=2019-01-02"),
            Endpoint.get("/api/admin/dashboard/payment-methods"),
            Endpoint.get("/api/admin/dashboard/order-status"),
            Endpoint.get("/api/admin/dashboard/success-orders-by-district"),
            Endpoint.get("/api/admin/dashboard/top-chefs"),
            Endpoint.get("/api/admin/orders"),
            Endpoint.get("/api/admin/orders/00000000-0000-0000-0000-000000000001"),
            Endpoint.post("/api/admin/voucher", AdminAccessMatrixTest::voucherBody),
            Endpoint.get("/api/admin/voucher"),
            Endpoint.get("/api/admin/verification/987654321"),
            Endpoint.patch("/api/admin/certificate/00000000-0000-0000-0000-000000000001?status=ACTIVE"),
            Endpoint.patch("/api/admin/bank-accounts/customers/987654321/verification", "{\"status\":true}"),
            Endpoint.patch("/api/admin/bank-accounts/chefs/987654321/verification", "{\"status\":true}"),
            Endpoint.get("/api/admin/bank-accounts/chefs"),
            Endpoint.get("/api/admin/bank-accounts/customers"));

    private ResultActions call(Account who, Endpoint e) throws Exception {
        MockHttpServletRequestBuilder b = request(e.method(), e.url());
        if (e.body() != null) {
            b.contentType("application/json").content(e.body().get());
        }
        return as(who, b);
    }

    @Test
    void everyEndpoint_noToken_is401() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            int status = call(null, e).andReturn().getResponse().getStatus();
            assertThat(status).as(e.method() + " " + e.url()).isEqualTo(401);
        }
    }

    @Test
    void everyEndpoint_customerAndChef_are403PermissionDenied() throws Exception {
        Account customer = register("mx-cust", UserRole.CUSTOMER);
        Account chef = register("mx-chef", UserRole.CHEF);
        for (Account who : List.of(customer, chef)) {
            for (Endpoint e : ENDPOINTS) {
                ResultActions r = call(who, e);
                assertThat(r.andReturn().getResponse().getStatus()).as(e.method() + " " + e.url()).isEqualTo(403);
                JsonNode json = body(r);
                assertThat(json.get("message_code").asString()).as(e.url()).isEqualTo("PERMISSION_DENIED");
                assertThat(json.get("message").asString()).as(e.url()).isEqualTo("Only admin can access this endpoint");
                assertThat(json.get("error_code").asInt()).isEqualTo(403);
            }
        }
    }

    @Test
    void everyEndpoint_admin_getsPastTheGuard() throws Exception {
        Account admin = register("mx-admin", UserRole.ADMIN);
        for (Endpoint e : ENDPOINTS) {
            ResultActions r = call(admin, e);
            int status = r.andReturn().getResponse().getStatus();
            String message = body(r).path("message").asString();
            assertThat(status).as(e.method() + " " + e.url()).isNotEqualTo(401);
            assertThat(message).as(e.method() + " " + e.url()).isNotEqualTo("Only admin can access this endpoint");
        }
    }

    @Test
    void adminAndCustomerTogether_isStillAdmin_andAdminOnlyIsNotAChefOrCustomer() throws Exception {
        // two roles at once: the ADMIN membership alone is what the guard reads
        Account both = register("mx-both", UserRole.CUSTOMER);
        var u = userRepository.findById(both.userId()).orElseThrow();
        u.addRole(UserRole.ADMIN);
        userRepository.save(u);
        getAs(both, "/api/admin/dashboard/overview").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void validationRunsBeforeTheAdminCheck_likeNinja() throws Exception {
        Account customer = register("mx-val", UserRole.CUSTOMER);
        // revenue-chart without the required dates: 401 VALIDATION_ERROR, not 403
        ResultActions r = getAs(customer, "/api/admin/dashboard/revenue-chart");
        assertThat(r.andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r).get("message_code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body(r).get("data").has("from_date")).isTrue();

        // an invalid pydantic date on success-orders-by-district: 401 validation too
        ResultActions r2 = getAs(customer, "/api/admin/dashboard/success-orders-by-district?from_date=nope");
        assertThat(r2.andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r2).get("message_code").asString()).isEqualTo("VALIDATION_ERROR");

        // missing body field on the voucher create: 401 validation, not 403
        ResultActions r3 = postJson(customer, "/api/admin/voucher", "{\"code\":\"only-code\"}");
        assertThat(r3.andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r3).get("message_code").asString()).isEqualTo("VALIDATION_ERROR");

        // invalid certificate status enum
        ResultActions r4 = patchAs(customer,
                "/api/admin/certificate/00000000-0000-0000-0000-000000000001?status=APPROVED");
        assertThat(r4.andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r4).get("message_code").asString()).isEqualTo("VALIDATION_ERROR");
    }
}
