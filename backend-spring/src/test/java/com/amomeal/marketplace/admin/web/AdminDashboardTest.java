package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Correctness of the dashboard aggregations against fixture orders with known totals. The database is
 * shared with every other test class, so the global figures are before/after deltas and the per-day /
 * per-district figures use far-past windows (2019) that nothing else writes to.
 */
class AdminDashboardTest extends AbstractAdminFullStackTest {

    @Autowired private ChefProfileRepository chefProfileRepository;
    @Autowired private AttachmentRepository attachmentRepository;

    private JsonNode data(Account admin, String url) throws Exception {
        return body(getAs(admin, url).andExpect(status().isOk())).get("data");
    }

    // ------------------------------------------------------------------ overview + order status

    @Test
    void overview_andOrderStatus_reflectFixtureOrdersExactly() throws Exception {
        Account admin = register("dash-admin", UserRole.ADMIN);
        JsonNode before = data(admin, "/api/admin/dashboard/overview");
        Map<String, Long> statusBefore = statusCounts(admin);
        long totalBefore = data(admin, "/api/admin/dashboard/order-status").get("total_orders").asLong();

        Account cust = register("dash-cust", UserRole.CUSTOMER);
        Account k1 = register("dash-k1", UserRole.CHEF);
        Account k2 = register("dash-k2", UserRole.CHEF);
        register("dash-k3-noorders", UserRole.CHEF);
        CustomerAddress addr = address(cust, "Quan 1");
        Instant now = Instant.now();
        order(cust, k1, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.HOLDING, "100000.50", now);   // revenue
        order(cust, k1, addr, PaymentMethod.PAYOS, OrderStatus.COMPLETED, PaymentStatus.RELEASED, "200000", now);   // revenue
        order(cust, k2, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "1000.25", now);     // revenue
        order(cust, k2, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.PENDING, "7000", now);        // not collected
        order(cust, k2, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.REFUNDED, "9000", now);       // refunded
        order(cust, k2, addr, PaymentMethod.PAYOS, OrderStatus.PENDING, PaymentStatus.HOLDING, "3000", now);        // not completed
        order(cust, k1, addr, PaymentMethod.PAYOS, OrderStatus.CANCELLED, PaymentStatus.REFUNDED, "5000", now);     // cancelled

        JsonNode after = data(admin, "/api/admin/dashboard/overview");
        assertThat(after.get("total_revenue").asDouble() - before.get("total_revenue").asDouble())
                .isCloseTo(301000.75, within(0.001));
        assertThat(after.get("total_orders").asLong() - before.get("total_orders").asLong()).isEqualTo(7);
        assertThat(after.get("new_users").asLong() - before.get("new_users").asLong()).isEqualTo(4); // cust + 3 chefs, not the admin
        assertThat(after.get("active_chefs").asLong() - before.get("active_chefs").asLong()).isEqualTo(2); // k3 has no orders

        Map<String, Long> statusAfter = statusCounts(admin);
        assertThat(delta(statusAfter, statusBefore, "COMPLETED")).isEqualTo(5);
        assertThat(delta(statusAfter, statusBefore, "PENDING")).isEqualTo(1);
        assertThat(delta(statusAfter, statusBefore, "CANCELLED")).isEqualTo(1);

        // cancellation rate = round(cancelled / total * 100, 2)
        JsonNode statusData = data(admin, "/api/admin/dashboard/order-status");
        long total = statusData.get("total_orders").asLong();
        assertThat(total - totalBefore).isEqualTo(7);
        long cancelled = statusAfter.get("CANCELLED");
        double expectedRate = Math.round((double) cancelled / total * 100 * 100.0) / 100.0;
        assertThat(after.get("cancellation_rate").asDouble()).isCloseTo(expectedRate, within(0.011));

        // every status row: percentage = count / total, ordered by count desc
        long previous = Long.MAX_VALUE;
        for (JsonNode row : statusData.get("data")) {
            long count = row.get("count").asLong();
            assertThat(count).isLessThanOrEqualTo(previous);
            previous = count;
            assertThat(row.get("percentage").asDouble()).isCloseTo((double) count / total * 100, within(0.006));
        }
    }

    private Map<String, Long> statusCounts(Account admin) throws Exception {
        Map<String, Long> m = new HashMap<>();
        for (JsonNode row : data(admin, "/api/admin/dashboard/order-status").get("data")) {
            m.put(row.get("status").asString(), row.get("count").asLong());
        }
        return m;
    }

    private static long delta(Map<String, Long> after, Map<String, Long> before, String key) {
        return after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }

    // ------------------------------------------------------------------ revenue chart

    @Test
    void revenueChart_bucketsByUtcDay_withInclusiveBoundaries_andOnlyCollectedCompletedOrders() throws Exception {
        Account admin = register("rev-admin", UserRole.ADMIN);
        Account cust = register("rev-cust", UserRole.CUSTOMER);
        Account chef = register("rev-chef", UserRole.CHEF);
        CustomerAddress addr = address(cust, "Quan 1");
        PaymentMethod m = PaymentMethod.COD;
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.HOLDING, "100000.50", Instant.parse("2019-03-10T23:30:00Z"));
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.RELEASED, "50000", Instant.parse("2019-03-10T00:00:00Z"));
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "70000", Instant.parse("2019-03-11T00:00:00Z"));
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.PENDING, "123", Instant.parse("2019-03-11T12:00:00Z"));
        order(cust, chef, addr, m, OrderStatus.CANCELLED, PaymentStatus.REFUNDED, "456", Instant.parse("2019-03-11T12:00:00Z"));
        order(cust, chef, addr, m, OrderStatus.PENDING, PaymentStatus.HOLDING, "789", Instant.parse("2019-03-11T12:00:00Z"));
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.HOLDING, "1", Instant.parse("2019-03-12T23:59:59Z"));
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "999", Instant.parse("2019-03-13T10:00:00Z")); // after range
        order(cust, chef, addr, m, OrderStatus.COMPLETED, PaymentStatus.HOLDING, "5", Instant.parse("2019-03-09T23:59:59Z"));  // before range

        JsonNode d = data(admin, "/api/admin/dashboard/revenue-chart?from_date=2019-03-10&to_date=2019-03-12");
        assertThat(d.get("from_date").asString()).isEqualTo("2019-03-10");
        assertThat(d.get("to_date").asString()).isEqualTo("2019-03-12");
        JsonNode rows = d.get("data");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("date").asString()).isEqualTo("2019-03-10");
        assertThat(rows.get(0).get("revenue").asDouble()).isCloseTo(150000.5, within(0.001));
        assertThat(rows.get(0).get("orders").asInt()).isEqualTo(2);
        assertThat(rows.get(1).get("date").asString()).isEqualTo("2019-03-11");
        assertThat(rows.get(1).get("revenue").asDouble()).isCloseTo(70000.0, within(0.001));
        assertThat(rows.get(1).get("orders").asInt()).isEqualTo(1);
        assertThat(rows.get(2).get("date").asString()).isEqualTo("2019-03-12");
        assertThat(rows.get(2).get("revenue").asDouble()).isCloseTo(1.0, within(0.001));

        // strptime accepts non-padded dates and the response echoes them normalized
        JsonNode padded = data(admin, "/api/admin/dashboard/revenue-chart?from_date=2019-3-10&to_date=2019-3-10");
        assertThat(padded.get("from_date").asString()).isEqualTo("2019-03-10");
        assertThat(padded.get("data")).hasSize(1);

        // a window with no orders: empty list, dates echoed
        JsonNode empty = data(admin, "/api/admin/dashboard/revenue-chart?from_date=2018-01-01&to_date=2018-01-31");
        assertThat(empty.get("data")).isEmpty();
        assertThat(empty.get("from_date").asString()).isEqualTo("2018-01-01");
    }

    @Test
    void revenueChart_malformedDate_isAnUncaught500_notAValidationError() throws Exception {
        Account admin = register("rev2-admin", UserRole.ADMIN);
        getAs(admin, "/api/admin/dashboard/revenue-chart?from_date=2019/03/10&to_date=2019-03-12")
                .andExpect(status().isInternalServerError());
        getAs(admin, "/api/admin/dashboard/revenue-chart?from_date=2019-02-30&to_date=2019-03-12")
                .andExpect(status().isInternalServerError());
    }

    // ------------------------------------------------------------------ payment methods

    @Test
    void paymentMethods_countsAndSumsOnlyCollectedTransactions() throws Exception {
        Account admin = register("pay-admin", UserRole.ADMIN);
        JsonNode before = data(admin, "/api/admin/dashboard/payment-methods");
        Account cust = register("pay-cust", UserRole.CUSTOMER);
        CustomerAddress addr = address(cust, "Quan 1");

        payment(checkout(cust, addr, PaymentMethod.COD), PaymentMethod.COD, PaymentStatus.SUCCESS, "100.00");
        payment(checkout(cust, addr, PaymentMethod.COD), PaymentMethod.COD, PaymentStatus.SUCCESS, "50.50");
        payment(checkout(cust, addr, PaymentMethod.PAYOS), PaymentMethod.PAYOS, PaymentStatus.HOLDING, "300.00");
        payment(checkout(cust, addr, PaymentMethod.PAYOS), PaymentMethod.PAYOS, PaymentStatus.RELEASED, "200.00");
        payment(checkout(cust, addr, PaymentMethod.PAYOS), PaymentMethod.PAYOS, PaymentStatus.PENDING, "999.00");   // not collected
        payment(checkout(cust, addr, PaymentMethod.COD), PaymentMethod.COD, PaymentStatus.FAILED, "77.00");         // not collected
        payment(checkout(cust, addr, PaymentMethod.PAYOS), PaymentMethod.PAYOS, PaymentStatus.REFUNDED, "5.00");    // not collected

        JsonNode after = data(admin, "/api/admin/dashboard/payment-methods");
        assertThat(after.get("total_orders").asLong() - before.get("total_orders").asLong()).isEqualTo(4);
        assertThat(count(after, "COD") - count(before, "COD")).isEqualTo(2);
        assertThat(count(after, "PAYOS") - count(before, "PAYOS")).isEqualTo(2);
        assertThat(amount(after, "COD") - amount(before, "COD")).isCloseTo(150.5, within(0.001));
        assertThat(amount(after, "PAYOS") - amount(before, "PAYOS")).isCloseTo(500.0, within(0.001));

        long total = after.get("total_orders").asLong();
        long previous = Long.MAX_VALUE;
        for (JsonNode row : after.get("data")) {
            long c = row.get("count").asLong();
            assertThat(c).isLessThanOrEqualTo(previous);
            previous = c;
            assertThat(row.get("percentage").asDouble()).isCloseTo((double) c / total * 100, within(0.006));
        }
    }

    private static long count(JsonNode d, String method) {
        for (JsonNode row : d.get("data")) {
            if (method.equals(row.get("payment_method").asString())) {
                return row.get("count").asLong();
            }
        }
        return 0;
    }

    private static double amount(JsonNode d, String method) {
        for (JsonNode row : d.get("data")) {
            if (method.equals(row.get("payment_method").asString())) {
                return row.get("total_amount").asDouble();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ districts

    @Test
    void successOrdersByDistrict_countsCompletedWithAddress_percentagesRoundedLikePython() throws Exception {
        Account admin = register("dist-admin", UserRole.ADMIN);
        Account cust = register("dist-cust", UserRole.CUSTOMER);
        Account chef = register("dist-chef", UserRole.CHEF);
        CustomerAddress q1 = address(cust, "Quận 1");
        CustomerAddress q3 = address(cust, "Quận 3");
        CustomerAddress q7 = address(cust, "Quận 7");
        PaymentMethod m = PaymentMethod.COD;
        for (int i = 0; i < 3; i++) {
            order(cust, chef, q1, m, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "10", Instant.parse("2019-04-10T10:00:00Z"));
        }
        for (int i = 0; i < 2; i++) {
            order(cust, chef, q3, m, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "10", Instant.parse("2019-04-20T10:00:00Z"));
        }
        order(cust, chef, q1, m, OrderStatus.CANCELLED, PaymentStatus.REFUNDED, "10", Instant.parse("2019-04-11T10:00:00Z"));  // not completed
        order(cust, chef, q7, m, OrderStatus.PENDING, PaymentStatus.PENDING, "10", Instant.parse("2019-04-11T10:00:00Z"));    // not completed
        order(cust, chef, null, m, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "10", Instant.parse("2019-04-12T10:00:00Z")); // no address
        order(cust, chef, q7, m, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "10", Instant.parse("2019-05-01T00:00:00Z"));   // May

        JsonNode april = data(admin, "/api/admin/dashboard/success-orders-by-district?from_date=2019-04-01&to_date=2019-04-30");
        assertThat(april.get("total_success_orders").asInt()).isEqualTo(5);
        assertThat(april.get("data")).hasSize(2);
        assertThat(april.get("data").get(0).get("district").asString()).isEqualTo("Quận 1");
        assertThat(april.get("data").get(0).get("success_orders").asInt()).isEqualTo(3);
        assertThat(april.get("data").get(0).get("percentage").asDouble()).isEqualTo(60.0);
        assertThat(april.get("data").get(1).get("district").asString()).isEqualTo("Quận 3");
        assertThat(april.get("data").get(1).get("percentage").asDouble()).isEqualTo(40.0);

        JsonNode both = data(admin, "/api/admin/dashboard/success-orders-by-district?from_date=2019-04-01&to_date=2019-05-31");
        assertThat(both.get("total_success_orders").asInt()).isEqualTo(6);
        assertThat(both.get("data")).hasSize(3);
        assertThat(both.get("data").get(1).get("percentage").asDouble()).isEqualTo(33.33);   // 2/6 -> 33.33
        assertThat(both.get("data").get(2).get("district").asString()).isEqualTo("Quận 7");
        assertThat(both.get("data").get(2).get("percentage").asDouble()).isEqualTo(16.67);   // 1/6 -> 16.67

        JsonNode none = data(admin, "/api/admin/dashboard/success-orders-by-district?from_date=2018-01-01&to_date=2018-01-02");
        assertThat(none.get("total_success_orders").asInt()).isZero();
        assertThat(none.get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ top chefs

    @Test
    void topChefs_ranksByCompletedOrders_withNamesRevenueAndAvatar() throws Exception {
        Account admin = register("top-admin", UserRole.ADMIN);
        Account cust = register("top-cust", UserRole.CUSTOMER);
        Account a = register("top-a", UserRole.CHEF);
        Account b = register("top-b", UserRole.CHEF);
        Account c = register("top-c", UserRole.CHEF);
        setName(a, "Alice", "Nguyen");
        setName(b, "Binh", "");
        CustomerAddress addr = address(cust, "Quan 1");

        Attachment avatar = attachmentRepository.save(Attachment.builder().type(AttachmentType.CHEF_AVATAR)
                .originalName("a.jpg").hashedName("a-" + UUID.randomUUID() + ".jpg").size(10).contentType("image/jpeg")
                .bucket("b").directory("avatar").publicUrl("https://cdn.example.test/avatar-a.jpg").isCompleted(true).build());
        chefProfileRepository.save(ChefProfile.builder().user(userRepository.getReferenceById(a.userId())).avatar(avatar).build());
        chefProfileRepository.save(ChefProfile.builder().user(userRepository.getReferenceById(b.userId())).build());

        for (int i = 0; i < 6; i++) {
            order(cust, a, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "10000.10", null);
        }
        for (int i = 0; i < 4; i++) {
            order(cust, b, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "500", null);
        }
        for (int i = 0; i < 2; i++) {
            order(cust, c, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "1", null);
        }
        for (int i = 0; i < 3; i++) {
            order(cust, c, addr, PaymentMethod.COD, OrderStatus.CANCELLED, PaymentStatus.REFUNDED, "1", null);  // not counted
        }

        JsonNode list = data(admin, "/api/admin/dashboard/top-chefs?limit=20");
        assertThat(list.size()).isLessThanOrEqualTo(20);
        long previous = Long.MAX_VALUE;
        JsonNode ra = null;
        JsonNode rb = null;
        JsonNode rc = null;
        for (JsonNode row : list) {
            long n = row.get("total_orders").asLong();
            assertThat(n).isLessThanOrEqualTo(previous);
            previous = n;
            long id = row.get("chef_id").asLong();
            if (id == a.userId()) {
                ra = row;
            } else if (id == b.userId()) {
                rb = row;
            } else if (id == c.userId()) {
                rc = row;
            }
        }
        assertThat(ra).isNotNull();
        assertThat(ra.get("chef_name").asString()).isEqualTo("Alice Nguyen");
        assertThat(ra.get("chef_email").asString()).isEqualTo(a.email());
        assertThat(ra.get("total_orders").asInt()).isEqualTo(6);
        assertThat(ra.get("total_revenue").asDouble()).isCloseTo(60000.6, within(0.001));
        assertThat(ra.get("avatar_url").asString()).isEqualTo("https://cdn.example.test/avatar-a.jpg");
        assertThat(rb).isNotNull();
        assertThat(rb.get("chef_name").asString()).isEqualTo("Binh");                 // "Binh " stripped
        assertThat(rb.get("total_orders").asInt()).isEqualTo(4);
        assertThat(rb.get("avatar_url").isNull()).isTrue();                            // profile without avatar
        assertThat(rc).isNotNull();
        assertThat(rc.get("chef_name").asString()).isEqualTo(c.username());            // no name -> username
        assertThat(rc.get("total_orders").asInt()).isEqualTo(2);
        assertThat(rc.get("avatar_url").isNull()).isTrue();                            // no profile at all

        // limit handling: default 5, capped at 20, 1, 0
        assertThat(data(admin, "/api/admin/dashboard/top-chefs").size()).isLessThanOrEqualTo(5);
        assertThat(data(admin, "/api/admin/dashboard/top-chefs?limit=100").size()).isLessThanOrEqualTo(20);
        JsonNode one = data(admin, "/api/admin/dashboard/top-chefs?limit=1");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).get("total_orders").asInt()).isGreaterThanOrEqualTo(6);
        assertThat(data(admin, "/api/admin/dashboard/top-chefs?limit=0")).isEmpty();
        getAs(admin, "/api/admin/dashboard/top-chefs?limit=abc").andExpect(status().isUnauthorized());   // validation error
    }
}
