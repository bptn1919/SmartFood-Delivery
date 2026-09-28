package com.amomeal.marketplace.admin.web;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.entity.UserRole;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/admin/orders} (filters, pagination, field shapes) and {@code /orders/{uid}} (detail). */
class AdminOrdersTest extends AbstractAdminFullStackTest {

    @Autowired private VoucherRepository voucherRepository;
    @Autowired private AppliedVoucherRepository appliedVoucherRepository;

    private record Fixture(Account admin, Account custA, Account custB, Account chef, Order o1, Order o2, Order o3) {
    }

    private void applied(Voucher v, Order o, Account user, VoucherReservationStatus st) {
        CustomUser u = userRepository.getReferenceById(user.userId());
        appliedVoucherRepository.save(AppliedVoucher.builder().voucher(v).user(u).orderUid(o.getUid())
                .voucherType(v.getVoucherType()).discountAmount(new BigDecimal("1000")).status(st).build());
    }

    private Voucher voucher(Account owner, String code, VoucherType type) {
        return voucherRepository.save(Voucher.builder().chef(userRepository.getReferenceById(owner.userId()))
                .code(code + System.nanoTime()).name(code).voucherType(type)
                .discountType(VoucherDiscountType.FIXED_AMOUNT).discountValue(new BigDecimal("1000"))
                .startDate(Instant.parse("2019-01-01T00:00:00Z")).endDate(Instant.parse("2099-01-01T00:00:00Z")).build());
    }

    private Fixture fixture() throws Exception {
        Account admin = register("ord-admin", UserRole.ADMIN);
        Account custA = register("ord-a", UserRole.CUSTOMER);
        Account custB = register("ord-b", UserRole.CUSTOMER);
        Account chef = register("ord-chef", UserRole.CHEF);
        setName(custA, "Lan", "Tran");
        setName(chef, "Bep", "Truong");
        CustomerAddress addr = address(custA, "Quận 1");

        Order o1 = order(custA, chef, addr, PaymentMethod.COD, OrderStatus.COMPLETED, PaymentStatus.SUCCESS, "100000",
                Instant.parse("2019-06-01T10:00:00Z"));
        o1.setSubTotal(new BigDecimal("95000"));
        o1.setTaxAndFees(new BigDecimal("8000"));
        o1.setDeliveryFee(new BigDecimal("12000"));
        o1.setPlatformSubtotalDiscount(new BigDecimal("5000"));
        o1.setPlatformShippingDiscount(new BigDecimal("3000"));
        o1.setShopDiscount(new BigDecimal("2000"));
        o1.setTotalDiscount(new BigDecimal("10000"));
        o1 = orderRepository.save(o1);
        item(o1, "Pho bo", 2, "30000.50");
        item(o1, "Tra da", 1, "5000");
        Voucher platform = voucher(custA, "PLAT", VoucherType.PLATFORM_SUBTOTAL);
        Voucher shop = voucher(custA, "SHOP", VoucherType.SHOP_VOUCHER);
        Voucher old = voucher(custA, "OLD", VoucherType.PLATFORM_SHIPPING);
        applied(platform, o1, custA, VoucherReservationStatus.RESERVED);
        applied(shop, o1, custA, VoucherReservationStatus.USED);
        applied(old, o1, custA, VoucherReservationStatus.CANCELLED);                 // never listed

        Order o2 = order(custB, chef, null, PaymentMethod.PAYOS, OrderStatus.PENDING, PaymentStatus.PENDING, "50000",
                Instant.parse("2019-06-02T10:00:00Z"));
        Order o3 = order(custA, null, addr, PaymentMethod.COD, OrderStatus.DRAFT, PaymentStatus.PENDING, "1000",
                Instant.parse("2019-06-03T10:00:00Z"));
        return new Fixture(admin, custA, custB, chef, o1, o2, o3);
    }

    private JsonNode list(Account admin, String query) throws Exception {
        return body(getAs(admin, "/api/admin/orders?" + query).andExpect(status().isOk())).get("data");
    }

    @Test
    void list_shapesEveryField_andJoinsVoucherCodes_andNamesFallBackToUsername() throws Exception {
        Fixture f = fixture();
        JsonNode d = list(f.admin(), "chef_email=" + f.chef().email());
        assertThat(d.get("total_rows").asInt()).isEqualTo(2);
        assertThat(d.get("total_pages").asInt()).isEqualTo(1);
        assertThat(d.get("current_page").asInt()).isEqualTo(1);
        assertThat(d.get("page_size").asInt()).isEqualTo(50);                      // ninja default

        JsonNode newest = d.get("content").get(0);                                 // created_at desc: o2 (06-02) then o1
        assertThat(newest.get("uid").asString()).isEqualTo(f.o2().getUid().toString());
        assertThat(newest.get("customer_name").asString()).isEqualTo(f.custB().username());   // no name -> username
        assertThat(newest.get("customer_email").asString()).isEqualTo(f.custB().email());
        assertThat(newest.get("payment_method").asString()).isEqualTo("PAYOS");
        assertThat(newest.get("voucher_code").isNull()).isTrue();
        assertThat(newest.get("total_discount").asDouble()).isZero();
        assertThat(newest.get("delivery_date").asString()).isEqualTo("2026-05-01");

        JsonNode o1 = d.get("content").get(1);
        assertThat(o1.get("uid").asString()).isEqualTo(f.o1().getUid().toString());
        assertThat(o1.get("customer_name").asString()).isEqualTo("Lan Tran");
        assertThat(o1.get("chef_name").asString()).isEqualTo("Bep Truong");
        assertThat(o1.get("chef_email").asString()).isEqualTo(f.chef().email());
        assertThat(o1.get("total_price").asDouble()).isEqualTo(100000.0);
        assertThat(o1.get("platform_subtotal_discount").asDouble()).isEqualTo(5000.0);
        assertThat(o1.get("platform_shipping_discount").asDouble()).isEqualTo(3000.0);
        assertThat(o1.get("shop_discount").asDouble()).isEqualTo(2000.0);
        assertThat(o1.get("total_discount").asDouble()).isEqualTo(10000.0);
        assertThat(o1.get("voucher_code").asString()).startsWith("PLAT").contains(", SHOP").doesNotContain("OLD");
        assertThat(o1.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(o1.get("payment_status").asString()).isEqualTo("SUCCESS");
        assertThat(o1.get("payment_method").asString()).isEqualTo("COD");
        assertThat(o1.get("created_at").asString()).isEqualTo("2019-06-01T10:00:00+00:00");     // Python isoformat
    }

    @Test
    void list_filters_customerEmail_status_paymentFields_andDateWindow() throws Exception {
        Fixture f = fixture();
        // customer email is a case-insensitive substring; custA has o1 and o3, newest first
        JsonNode a = list(f.admin(), "customer_email=" + f.custA().email().toUpperCase());
        assertThat(a.get("total_rows").asInt()).isEqualTo(2);
        assertThat(a.get("content").get(0).get("uid").asString()).isEqualTo(f.o3().getUid().toString());
        assertThat(a.get("content").get(1).get("uid").asString()).isEqualTo(f.o1().getUid().toString());

        // status is upper-cased: "completed" matches COMPLETED
        JsonNode completed = list(f.admin(), "chef_email=" + f.chef().email() + "&status=completed");
        assertThat(completed.get("total_rows").asInt()).isEqualTo(1);
        assertThat(completed.get("content").get(0).get("uid").asString()).isEqualTo(f.o1().getUid().toString());

        assertThat(list(f.admin(), "chef_email=" + f.chef().email() + "&payment_method=payos").get("total_rows").asInt()).isEqualTo(1);
        assertThat(list(f.admin(), "customer_email=" + f.custB().email() + "&payment_status=pending").get("total_rows").asInt()).isEqualTo(1);
        assertThat(list(f.admin(), "customer_email=" + f.custB().email() + "&payment_status=success").get("total_rows").asInt()).isZero();

        // inclusive date window on created_at (UTC days)
        JsonNode from = list(f.admin(), "chef_email=" + f.chef().email() + "&from_date=2019-06-02");
        assertThat(from.get("total_rows").asInt()).isEqualTo(1);
        assertThat(from.get("content").get(0).get("uid").asString()).isEqualTo(f.o2().getUid().toString());
        JsonNode to = list(f.admin(), "chef_email=" + f.chef().email() + "&to_date=2019-06-01");
        assertThat(to.get("total_rows").asInt()).isEqualTo(1);
        assertThat(to.get("content").get(0).get("uid").asString()).isEqualTo(f.o1().getUid().toString());

        // a malformed date is silently ignored (Django's FilterSchema returns Q()), not an error
        assertThat(list(f.admin(), "chef_email=" + f.chef().email() + "&from_date=abc&to_date=31-12-2019")
                .get("total_rows").asInt()).isEqualTo(2);

        // an unknown status matches nothing: one empty page
        JsonNode none = list(f.admin(), "chef_email=" + f.chef().email() + "&status=NOPE");
        assertThat(none.get("total_rows").asInt()).isZero();
        assertThat(none.get("total_pages").asInt()).isEqualTo(1);
        assertThat(none.get("content")).isEmpty();
    }

    @Test
    void list_paginates_likeDjangoPaginator() throws Exception {
        Fixture f = fixture();
        String q = "chef_email=" + f.chef().email() + "&page_size=1";
        JsonNode p1 = list(f.admin(), q + "&page=1");
        assertThat(p1.get("total_rows").asInt()).isEqualTo(2);
        assertThat(p1.get("total_pages").asInt()).isEqualTo(2);
        assertThat(p1.get("content")).hasSize(1);
        assertThat(p1.get("page_size").asInt()).isEqualTo(1);
        JsonNode p2 = list(f.admin(), q + "&page=2");
        assertThat(p2.get("content")).hasSize(1);
        assertThat(p2.get("current_page").asInt()).isEqualTo(2);
        assertThat(p2.get("content").get(0).get("uid").asString()).isNotEqualTo(p1.get("content").get(0).get("uid").asString());
        JsonNode past = list(f.admin(), q + "&page=3");
        assertThat(past.get("content")).isEmpty();                                 // past the end: empty, not an error
        assertThat(past.get("current_page").asInt()).isEqualTo(3);
        assertThat(past.get("total_rows").asInt()).isEqualTo(2);
        getAs(f.admin(), "/api/admin/orders?page=0").andExpect(status().isInternalServerError());       // EmptyPage in Django
        getAs(f.admin(), "/api/admin/orders?page_size=0").andExpect(status().isInternalServerError());  // ZeroDivisionError
        getAs(f.admin(), "/api/admin/orders?page=abc").andExpect(status().isUnauthorized());             // validation
    }

    @Test
    void detail_returnsFullOrder_withItemsAddressVouchersAndIsoTimestamps() throws Exception {
        Fixture f = fixture();
        JsonNode d = body(getAs(f.admin(), "/api/admin/orders/" + f.o1().getUid()).andExpect(status().isOk())).get("data");
        assertThat(d.get("uid").asString()).isEqualTo(f.o1().getUid().toString());
        assertThat(d.get("customer_id").asLong()).isEqualTo(f.custA().userId());
        assertThat(d.get("customer_name").asString()).isEqualTo("Lan Tran");
        assertThat(d.get("customer_email").asString()).isEqualTo(f.custA().email());
        assertThat(d.get("customer_phone").asString()).isEqualTo("0900000099");
        assertThat(d.get("chef_id").asLong()).isEqualTo(f.chef().userId());
        assertThat(d.get("chef_name").asString()).isEqualTo("Bep Truong");
        assertThat(d.get("items")).hasSize(2);
        assertThat(d.get("items").get(0).get("dish_name").asString()).isEqualTo("Pho bo");
        assertThat(d.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(d.get("items").get(0).get("price").asDouble()).isEqualTo(30000.5);
        assertThat(d.get("items").get(0).get("subtotal").asDouble()).isEqualTo(60001.0);
        assertThat(d.get("items").get(0).get("dish_image_url").asString()).isEqualTo("https://cdn.example.test/Pho bo.jpg");
        assertThat(d.get("sub_total").asDouble()).isEqualTo(95000.0);
        assertThat(d.get("tax_and_fees").asDouble()).isEqualTo(8000.0);
        assertThat(d.get("delivery_fee").asDouble()).isEqualTo(12000.0);
        assertThat(d.get("total_discount").asDouble()).isEqualTo(10000.0);
        assertThat(d.get("total_price").asDouble()).isEqualTo(100000.0);
        assertThat(d.get("voucher_code").asString()).contains(", ").doesNotContain("OLD");
        assertThat(d.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(d.get("payment_method").asString()).isEqualTo("COD");
        assertThat(d.get("delivery_address").asString()).isEqualTo("Nguyen Hue, Ben Nghe, Quận 1, HCMC");   // no `address` part, like Django
        assertThat(d.get("delivery_date").asString()).isEqualTo("2026-05-01");
        assertThat(d.get("delivery_time").asString()).isEqualTo("18:30:00");                                // HH:MM:SS even at :00 seconds
        assertThat(d.get("created_at").asString()).isEqualTo("2019-06-01T10:00:00+00:00");
        assertThat(d.get("updated_at").asString()).endsWith("+00:00");

        // an order with no chef and no address
        JsonNode d2 = body(getAs(f.admin(), "/api/admin/orders/" + f.o2().getUid()).andExpect(status().isOk())).get("data");
        assertThat(d2.get("chef_id").isNull()).isFalse();                                                   // o2 has the chef
        assertThat(d2.get("delivery_address").isNull()).isTrue();
        JsonNode d3 = body(getAs(f.admin(), "/api/admin/orders/" + f.o3().getUid()).andExpect(status().isOk())).get("data");
        assertThat(d3.get("chef_id").isNull()).isTrue();
        assertThat(d3.get("chef_name").isNull()).isTrue();
        assertThat(d3.get("items")).isEmpty();
    }

    @Test
    void detail_unknownOrder_is403PermissionDenied_notA404_andMalformedUidIs500() throws Exception {
        Account admin = register("ord2-admin", UserRole.ADMIN);
        JsonNode nf = body(getAs(admin, "/api/admin/orders/" + UUID.randomUUID()).andExpect(status().isForbidden()));
        assertThat(nf.get("message_code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(nf.get("message").asString()).isEqualTo("Order not found");
        getAs(admin, "/api/admin/orders/not-a-uuid").andExpect(status().isInternalServerError());
    }
}
