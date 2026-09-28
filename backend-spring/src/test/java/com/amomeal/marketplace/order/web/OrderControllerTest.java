package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack tests of the order module over real HTTP, real Postgres and real
 * Redis — every inventory assertion reads the actual {@code DishAvailability}
 * row, the actual {@code StockReservation} ledger row and the actual Redis
 * counter, not mocks.
 */
class OrderControllerTest extends AbstractOrderFullStackTest {

    @Autowired VoucherRepository voucherRepository;
    @Autowired AppliedVoucherRepository appliedVoucherRepository;

    // =====================================================================
    // COD: the whole happy lifecycle
    // =====================================================================

    @Test
    void cod_fullLifecycle_checkoutPlaceConfirmProcessDeliverComplete() throws Exception {
        Account chef = chef("chefcod");
        Account customer = customerWithAddress("cuscod");
        Dish pho = dish(chef, "Phở", "60000", 10);
        selectInCart(customer, pho, 3);

        // checkout: DRAFT order, prices snapshotted, no stock touched
        JsonNode co = data(call(post("/api/checkouts/"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error_code").value(0))
                .andExpect(jsonPath("$.data.payment_method").value("COD"))
                .andExpect(jsonPath("$.data.sub_total").value(180000))
                .andExpect(jsonPath("$.data.tax_and_fees").value(18000))
                .andExpect(jsonPath("$.data.delivery_fee").value(30000))
                .andExpect(jsonPath("$.data.total_price").value(228000))
                .andExpect(jsonPath("$.data.orders[0].items[0].quantity").value(3)));
        UUID checkoutUid = UUID.fromString(co.get("uid").asString());
        Order order = onlyOrder(checkoutUid);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(reservationsOf(order)).isEmpty();
        assertThat(committed(pho)).isEqualTo(10);

        // place-order (COD): reserve + confirm immediately -> permanent deduction
        call(post("/api/checkouts/" + checkoutUid + "/place-order"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").value(checkoutUid.toString()));
        order = reload(order.getUid());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getDeliveryAddressText()).isEqualTo("7, Lê Lợi, Bến Nghé, Q1, HCM");
        assertThat(order.getDeliveryName()).isEqualTo("cuscod Nguyen");
        assertThat(reservationsOf(order)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(committed(pho)).isEqualTo(7);
        assertThat(redisCounter(pho)).isEqualTo(7L);
        assertThat(cartService.getSelectedCartItemsByUser(customer.user())).isEmpty();

        String base = "/api/chef/orders/" + order.getUid();
        call(post(base + "/confirm"), chef).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED_SHOP"))
                .andExpect(jsonPath("$.data.chef_name").value(chef.user().getUsername()))
                .andExpect(jsonPath("$.data.chef_address").value("12 Bếp St, W1, D1, HCM"));
        // illegal jump straight to complete -> Django ValueError -> 500
        call(post(base + "/complete"), chef).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));
        call(post(base + "/start-processing"), chef).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        call(post(base + "/start-delivery"), chef).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERING"));
        call(post(base + "/complete"), chef).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // COMPLETED is terminal; the customer can no longer cancel; stock untouched by lifecycle
        call(post("/api/orders/" + order.getUid() + "/cancel"), customer).andExpect(status().isInternalServerError());
        assertThat(committed(pho)).isEqualTo(7);

        // sold_count now feeds dish's DishStatsProvider seam
        call(get("/api/dishes/top"), customer).andExpect(status().isOk());
    }

    // =====================================================================
    // Chef ownership + role scoping
    // =====================================================================

    @Test
    void chefActions_requireBeingTheOrdersChef_evenForAdmin() throws Exception {
        Account chef = chef("owner");
        Account otherChef = chef("intruder");
        Account admin = register("admin", UserRole.ADMIN);
        Account customer = customerWithAddress("cusown");
        Dish dish = dish(chef, "Bún", "50000", 5);
        selectInCart(customer, dish, 1);
        UUID checkoutUid = checkout(customer);
        call(post("/api/checkouts/" + checkoutUid + "/place-order"), customer).andExpect(status().isOk());
        UUID orderUid = onlyOrder(checkoutUid).getUid();

        for (Account stranger : List.of(otherChef, admin, customer)) {
            call(post("/api/chef/orders/" + orderUid + "/confirm"), stranger)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"))
                    .andExpect(jsonPath("$.data").value("You don't have permission to confirm this order"));
        }
        call(post("/api/chef/orders/" + orderUid + "/reject"), otherChef).andExpect(status().isForbidden());
        call(post("/api/chef/orders/" + UUID.randomUUID() + "/confirm"), chef)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("ORDER_NOT_FOUND"));
        assertThat(reload(orderUid).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void listEndpoints_areScopedByDjangoRole_customerChefAdmin() throws Exception {
        Account chefA = chef("scopea");
        Account chefB = chef("scopeb");
        Account alice = customerWithAddress("alice");
        Account bob = customerWithAddress("bob");
        Account admin = register("scopeadmin", UserRole.ADMIN);
        Dish da = dish(chefA, "A", "40000", 5);
        Dish db = dish(chefB, "B", "40000", 5);
        selectInCart(alice, da, 1);
        UUID coA = checkout(alice);
        call(post("/api/checkouts/" + coA + "/place-order"), alice).andExpect(status().isOk());
        selectInCart(bob, db, 1);
        UUID coB = checkout(bob);
        call(post("/api/checkouts/" + coB + "/place-order"), bob).andExpect(status().isOk());
        UUID aliceOrder = onlyOrder(coA).getUid();
        UUID bobOrder = onlyOrder(coB).getUid();

        // customer: own orders, wrapped one-per-element with chef_info
        JsonNode aliceList = data(call(get("/api/orders/"), alice).andExpect(status().isOk()));
        assertThat(aliceList).hasSize(1);
        assertThat(aliceList.get(0).get("orders").get(0).get("uid").asString()).isEqualTo(aliceOrder.toString());
        assertThat(aliceList.get(0).get("chef_info").get("chef_id").asLong()).isEqualTo(chefA.user().getId());

        // chef: only orders they cook, on both list endpoints
        assertThat(uids(data(call(get("/api/chef/orders/"), chefB).andExpect(status().isOk()))))
                .containsExactly(bobOrder.toString());

        // admin: everything (get_user_role -> ADMIN -> unscoped)
        List<String> adminSees = new java.util.ArrayList<>();
        for (JsonNode wrapper : data(call(get("/api/orders/"), admin))) {
            adminSees.add(wrapper.get("orders").get(0).get("uid").asString());
        }
        assertThat(adminSees).contains(aliceOrder.toString(), bobOrder.toString());

        // /customer is owner-scoped for everyone; DRAFT never listed
        assertThat(data(call(get("/api/orders/customer"), chefA))).isEmpty();
        selectInCart(alice, da, 1);
        checkout(alice); // leaves a DRAFT
        assertThat(data(call(get("/api/orders/customer"), alice))).hasSize(1);

        // status filter; invalid status silently ignored (Django returns Q())
        assertThat(data(call(get("/api/orders/").param("status", "COMPLETED"), alice))).isEmpty();
        assertThat(data(call(get("/api/orders/").param("status", "NOT_A_STATUS"), alice))).hasSize(1);
        // search by accent-stripped dish name
        assertThat(data(call(get("/api/orders/").param("search", "zzz-no-match"), alice))).isEmpty();

        mockMvc.perform(get("/api/orders/")).andExpect(status().isUnauthorized());
    }

    private static List<String> uids(JsonNode array) {
        List<String> result = new java.util.ArrayList<>();
        array.forEach(n -> result.add(n.get("uid").asString()));
        return result;
    }

    // =====================================================================
    // Cancellation <-> inventory
    // =====================================================================

    @Test
    void customerCancel_afterCodConfirm_restoresDishAvailability_andLedgerGoesCancelled() throws Exception {
        Account chef = chef("cancelchef");
        Account customer = customerWithAddress("cancelcus");
        Dish dish = dish(chef, "Cơm", "45000", 4);
        selectInCart(customer, dish, 2);
        UUID co = checkout(customer);
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        Order order = onlyOrder(co);
        assertThat(committed(dish)).isEqualTo(2);

        call(post("/api/orders/" + order.getUid() + "/cancel").param("reason", "changed mind"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(committed(dish)).isEqualTo(4);
        assertThat(reservationsOf(reload(order.getUid()))).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.CANCELLED);

        // a second cancel is refused (CANCELLED not customer-cancellable) and never double-credits
        call(post("/api/orders/" + order.getUid() + "/cancel"), customer).andExpect(status().isInternalServerError());
        assertThat(committed(dish)).isEqualTo(4);
    }

    @Test
    void chefReject_pending_releasesStock_andCustomerCannotCancelOnceChefAccepted() throws Exception {
        Account chef = chef("rejchef");
        Account customer = customerWithAddress("rejcus");
        Dish dish = dish(chef, "Gỏi", "30000", 3);
        selectInCart(customer, dish, 1);
        UUID co = checkout(customer);
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        UUID orderUid = onlyOrder(co).getUid();

        call(post("/api/chef/orders/" + orderUid + "/reject").param("reason", "out of herbs"), chef)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(committed(dish)).isEqualTo(3);

        selectInCart(customer, dish, 1);
        UUID co2 = checkout(customer);
        call(post("/api/checkouts/" + co2 + "/place-order"), customer).andExpect(status().isOk());
        UUID order2 = onlyOrder(co2).getUid();
        call(post("/api/chef/orders/" + order2 + "/confirm"), chef).andExpect(status().isOk());
        call(post("/api/orders/" + order2 + "/cancel"), customer).andExpect(status().isInternalServerError());
        call(post("/api/chef/orders/" + order2 + "/reject"), chef).andExpect(status().isInternalServerError());
        assertThat(reload(order2).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SHOP);
        assertThat(committed(dish)).isEqualTo(2);
    }

    // =====================================================================
    // Oversell / multi-item rollback / repeat place-order
    // =====================================================================

    @Test
    void lastUnit_contendedByManyCustomers_exactlyOneGetsIt() throws Exception {
        Account chef = chef("hotchef");
        Dish hot = dish(chef, "Bánh", "20000", 1);
        int n = 6;
        List<Account> customers = new java.util.ArrayList<>();
        List<UUID> checkouts = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Account c = customerWithAddress("race" + i);
            selectInCart(c, hot, 1);
            customers.add(c);
            checkouts.add(checkout(c));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<Integer>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                Account c = customers.get(i);
                UUID co = checkouts.get(i);
                tasks.add(() -> call(post("/api/checkouts/" + co + "/place-order"), c).andReturn().getResponse().getStatus());
            }
            int ok = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                if (f.get() == 200) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(committed(hot)).isZero();
        assertThat(redisCounter(hot)).isZero();
        long pending = checkouts.stream().map(this::onlyOrder).filter(o -> o.getStatus() == OrderStatus.PENDING).count();
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void secondItemOutOfStock_rollsBackTheFirstHold_andOrderStaysDraft() throws Exception {
        Account chef = chef("multichef");
        Account customer = customerWithAddress("multicus");
        Dish plenty = dish(chef, "Nhiều", "10000", 10);
        Dish scarce = dish(chef, "Ít", "10000", 1);
        selectInCart(customer, plenty, 2);
        selectInCart(customer, scarce, 3);
        UUID co = checkout(customer);

        call(post("/api/checkouts/" + co + "/place-order"), customer)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message_code").value("CONTACT_ADMIN_FOR_SUPPORT"));

        Order order = onlyOrder(co);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        // whichever item was held first got RELEASED; nothing CONFIRMED; nothing deducted
        assertThat(reservationsOf(order)).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(StockReservationStatus.RELEASED));
        assertThat(committed(plenty)).isEqualTo(10);
        assertThat(committed(scarce)).isEqualTo(1);
        Long plentyCounter = redisCounter(plenty);
        assertThat(plentyCounter == null || plentyCounter == 10L).isTrue();
        // selected cart items are kept for a retry
        assertThat(cartService.getSelectedCartItemsByUser(customer.user())).hasSize(2);
    }

    @Test
    void placingTheSameCodCheckoutTwice_neverDeductsTwice() throws Exception {
        Account chef = chef("twicechef");
        Account customer = customerWithAddress("twicecus");
        Dish dish = dish(chef, "Chè", "15000", 5);
        selectInCart(customer, dish, 2);
        UUID co = checkout(customer);
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isInternalServerError());
        assertThat(committed(dish)).isEqualTo(3);
        assertThat(redisCounter(dish)).isEqualTo(3L);
    }

    // =====================================================================
    // PayOS branch (payment module not ported: session stub, holds stay RESERVED)
    // =====================================================================

    @Test
    void payos_requiresVerifiedChefs_holdsStayReserved_cancelCreditsRedisBack() throws Exception {
        Account chef = chef("payoschef");
        Account customer = customerWithAddress("payoscus");
        Dish dish = dish(chef, "Lẩu", "250000", 5);
        selectInCart(customer, dish, 2);
        UUID co = checkout(customer);

        call(patch("/api/checkouts/" + co + "/payment-method").contentType("application/json").content("\"PAYOS\""), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.data").value(
                        "Some chefs are not verified for bank transfer. Please choose COD payment method."));
        verifyChefBank(chef);
        call(patch("/api/checkouts/" + co + "/payment-method").contentType("application/json").content("\"PAYOS\""), customer)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payment_method").value("PAYOS"));

        call(post("/api/checkouts/" + co + "/place-order").param("bank_code", "VCB"), customer)
                .andExpect(status().isOk());
        Order order = onlyOrder(co);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationsOf(order)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(committed(dish)).isEqualTo(5);      // Postgres untouched until the webhook
        assertThat(redisCounter(dish)).isEqualTo(3L);  // Redis holds the 2 units

        // chef cannot confirm an unpaid PayOS order
        call(post("/api/chef/orders/" + order.getUid() + "/confirm"), chef).andExpect(status().isInternalServerError());

        call(post("/api/orders/" + order.getUid() + "/cancel"), customer).andExpect(status().isOk());
        assertThat(reservationsOf(reload(order.getUid()))).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.RELEASED);
        assertThat(redisCounter(dish)).isEqualTo(5L);
        assertThat(committed(dish)).isEqualTo(5);
    }

    // =====================================================================
    // Vouchers
    // =====================================================================

    private Voucher voucher(Account owner, VoucherType type, VoucherDiscountType discountType, String value) {
        return voucherRepository.save(Voucher.builder().chef(owner.user())
                .code(("V" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase())
                .name("v").voucherType(type).discountType(discountType).discountValue(new BigDecimal(value))
                .startDate(Instant.now().minus(1, ChronoUnit.DAYS)).endDate(Instant.now().plus(1, ChronoUnit.DAYS))
                .usageLimit(100).usageLimitPerUser(5).build());
    }

    private List<AppliedVoucher> appliedFor(UUID orderUid) {
        return appliedVoucherRepository.findAll().stream().filter(a -> orderUid.equals(a.getOrderUid())).toList();
    }

    @Test
    void shopVoucher_appliedThenCodPlaced_isUsed_thenCancelledWithTheOrder() throws Exception {
        Account chef = chef("vchef");
        Account customer = customerWithAddress("vcus");
        Dish dish = dish(chef, "Mì", "100000", 5);
        selectInCart(customer, dish, 1);
        UUID co = checkout(customer);
        UUID orderUid = onlyOrder(co).getUid();
        Voucher shop = voucher(chef, VoucherType.SHOP_VOUCHER, VoucherDiscountType.FIXED_AMOUNT, "20000");

        call(post("/api/orders/" + orderUid + "/apply-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + shop.getCode() + "\"}"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shop_discount").value(20000))
                .andExpect(jsonPath("$.data.total_discount").value(20000))
                .andExpect(jsonPath("$.data.total_price").value(120000)) // 100k + 10k tax + 30k ship - 20k
                .andExpect(jsonPath("$.data.voucher_code").value(shop.getCode()));
        call(post("/api/orders/" + orderUid + "/apply-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + shop.getCode() + "\"}"), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message_code").value("VOUCHER_INVALID"))
                .andExpect(jsonPath("$.data").value("Order đã có shop voucher rồi"));
        assertThat(appliedFor(orderUid)).singleElement().satisfies(a -> {
            assertThat(a.getStatus()).isEqualTo(VoucherReservationStatus.RESERVED);
            assertThat(a.getCheckoutUid()).isEqualTo(co); // needed for checkout-level recalculation
        });

        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        assertThat(appliedFor(orderUid)).singleElement().satisfies(a -> {
            assertThat(a.getStatus()).isEqualTo(VoucherReservationStatus.USED);
            assertThat(a.getReservationExpiresAt()).isNull();
        });

        call(post("/api/orders/" + orderUid + "/cancel"), customer).andExpect(status().isOk());
        assertThat(appliedFor(orderUid)).singleElement()
                .extracting(AppliedVoucher::getStatus).isEqualTo(VoucherReservationStatus.CANCELLED);
    }

    @Test
    void shopVoucher_belowMinOrderAmount_isRejected() throws Exception {
        Account chef = chef("minchef");
        Account customer = customerWithAddress("mincus");
        Dish dish = dish(chef, "Xôi", "20000", 5);
        selectInCart(customer, dish, 1);
        UUID orderUid = onlyOrder(checkout(customer)).getUid();
        Voucher shop = voucher(chef, VoucherType.SHOP_VOUCHER, VoucherDiscountType.FIXED_AMOUNT, "5000");
        shop.setMinOrderAmount(new BigDecimal("50000"));
        voucherRepository.save(shop);

        call(post("/api/orders/" + orderUid + "/apply-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + shop.getCode() + "\"}"), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.startsWith(
                        "Giá trị của đơn hàng không đủ điều kiện áp voucher.")));
        assertThat(appliedFor(orderUid)).isEmpty();
    }

    @Test
    void platformSubtotalVoucher_isAllocatedProRataAcrossChefs() throws Exception {
        Account chefA = chef("plata");
        Account chefB = chef("platb");
        Account admin = register("platadmin", UserRole.ADMIN);
        Account customer = customerWithAddress("platcus");
        selectInCart(customer, dish(chefA, "A", "100000", 5), 1);
        selectInCart(customer, dish(chefB, "B", "300000", 5), 1);
        UUID co = checkout(customer);
        Voucher platform = voucher(admin, VoucherType.PLATFORM_SUBTOTAL, VoucherDiscountType.FIXED_AMOUNT, "40000");

        JsonNode resp = data(call(post("/api/checkouts/" + co + "/apply-platform-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + platform.getCode() + "\",\"voucher_type\":\"PLATFORM_SUBTOTAL\"}"), customer)
                .andExpect(status().isOk()));
        assertThat(resp.get("platform_subtotal_discount").decimalValue()).isEqualByComparingTo("40000");
        assertThat(resp.get("total_discount").decimalValue()).isEqualByComparingTo("40000");
        // A: 100k (30k ship, 10k tax), B: 300k (15k ship, 30k tax) => 485k - 40k
        assertThat(resp.get("total_price").decimalValue()).isEqualByComparingTo("445000");
        BigDecimal a = null;
        BigDecimal b = null;
        for (JsonNode o : resp.get("orders")) {
            BigDecimal alloc = o.get("platform_subtotal_discount").decimalValue();
            if (o.get("sub_total").decimalValue().compareTo(new BigDecimal("100000")) == 0) {
                a = alloc;
            } else {
                b = alloc;
            }
        }
        assertThat(a).isEqualByComparingTo("10000.00");
        assertThat(b).isEqualByComparingTo("30000.00");

        call(post("/api/checkouts/" + co + "/apply-platform-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + platform.getCode() + "\",\"voucher_type\":\"PLATFORM_SUBTOTAL\"}"), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value("Đã apply PLATFORM_SUBTOTAL voucher cho checkout này rồi"));
    }

    // =====================================================================
    // Checkout edits + error paths + preserved quirks
    // =====================================================================

    @Test
    void checkoutEdits_profileTimeAddressAndDeliveryTypes() throws Exception {
        Account chef = chef("editchef");
        Account customer = customerWithAddress("editcus");
        selectInCart(customer, dish(chef, "Canh", "50000", 5), 1);
        UUID co = checkout(customer);

        call(patch("/api/checkouts/" + co + "/profile").contentType("application/json")
                .content("{\"full_name\":\"Người Nhận\",\"phone_number\":\"0999\"}"), customer)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.full_name").value("Người Nhận"));
        call(patch("/api/checkouts/" + co + "/delivery-time").contentType("application/json").content("\"18:30:00\""), customer)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_time").value("18:30:00"));

        Long secondAddress = addressRepository.save(com.amomeal.marketplace.profile.entity.CustomerAddress.builder()
                .user(customer.user()).address("99").street("Nguyễn Huệ").ward("W").district("Q1").city("HCM")
                .latitude(10.7).longitude(106.7).build()).getId();
        call(patch("/api/checkouts/" + co + "/delivery-address/" + secondAddress), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_address").value("99, Nguyễn Huệ, W, Q1, HCM"));
        // someone else's / missing address: Django's .get() DoesNotExist, uncaught 500
        call(patch("/api/checkouts/" + co + "/delivery-address/999999999"), customer)
                .andExpect(status().isInternalServerError());

        String types = "{\"sub_orders\":[{\"chef_id\":%d,\"delivery_type\":\"%s\"}]}";
        call(patch("/api/checkouts/" + co + "/delivery-types").contentType("application/json")
                .content(types.formatted(chef.user().getId(), "SELF_PICKUP")), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_fee").value(0))
                .andExpect(jsonPath("$.data.total_price").value(55000));
        // THIRD_PARTY quotes Ahamove; unreachable in tests -> Django's 15000 fallback
        call(patch("/api/checkouts/" + co + "/delivery-types").contentType("application/json")
                .content(types.formatted(chef.user().getId(), "THIRD_PARTY")), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_fee").value(15000))
                .andExpect(jsonPath("$.data.total_price").value(70000));
    }

    @Test
    void checkout_errorPaths() throws Exception {
        Account noCart = customerWithAddress("nocart");
        call(post("/api/checkouts/"), noCart).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("CART_ITEM_NOT_FOUND"));

        Account chef = chef("errchef");
        Account noAddress = register("noaddr", UserRole.CUSTOMER);
        selectInCart(noAddress, dish(chef, "X", "10000", 5), 1);
        call(post("/api/checkouts/"), noAddress).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message_code").value("CUSTOMER_ADDRESS_NOT_FOUND"));

        call(post("/api/checkouts/" + UUID.randomUUID() + "/place-order"), noAddress)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("ORDER_NOT_FOUND"));
        call(get("/api/orders/" + UUID.randomUUID()), noAddress)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message_code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/checkouts/")).andExpect(status().isUnauthorized());
    }

    @Test
    void recheckout_replacesTheOldDraft_andCascadesItsVoucherRows() throws Exception {
        Account chef = chef("redochef");
        Account customer = customerWithAddress("redocus");
        Dish dish = dish(chef, "Súp", "100000", 5);
        selectInCart(customer, dish, 1);
        UUID first = checkout(customer);
        UUID firstOrder = onlyOrder(first).getUid();
        Voucher shop = voucher(chef, VoucherType.SHOP_VOUCHER, VoucherDiscountType.FIXED_AMOUNT, "10000");
        call(post("/api/orders/" + firstOrder + "/apply-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + shop.getCode() + "\"}"), customer).andExpect(status().isOk());

        UUID second = checkout(customer);
        assertThat(orderRepository.findById(firstOrder)).isEmpty();
        assertThat(appliedFor(firstOrder)).isEmpty(); // Django's FK CASCADE, emulated
        assertThat(onlyOrder(second).getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void preservedQuirk_orderOfAChefWithoutChefProfile_cannotBeRead() throws Exception {
        Account bareChef = register("bare", UserRole.CHEF); // no ChefProfile row
        Account customer = customerWithAddress("barecus");
        selectInCart(customer, dish(bareChef, "Y", "10000", 5), 1);
        UUID co = checkout(customer); // CheckoutResponse shape does not touch chef_profile
        UUID orderUid = onlyOrder(co).getUid();
        // Django: UnboundLocalError on chef_lat in to_response_with_info -> 500
        call(get("/api/orders/" + orderUid), customer).andExpect(status().isInternalServerError());
    }
}
