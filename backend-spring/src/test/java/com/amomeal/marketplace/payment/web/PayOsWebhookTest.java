package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.order.service.OrderPaymentGateway;
import com.amomeal.marketplace.payment.entity.PaymentTransactionEvent;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.service.PaymentOrderPaymentGateway;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The PayOS webhook / return endpoints over real HTTP, in the DEFAULT mode (the intended
 * behavior since the user chose "fix" for payment open question #1). The one test that pins
 * Django's actual buggy branch switches {@code PaymentProperties#preserveDjangoConfirmOutcomeBug}
 * on explicitly. The full escrow lifecycle is covered by {@link PayOsEscrowLifecycleTest}.
 */
class PayOsWebhookTest extends AbstractPaymentFullStackTest {

    @Autowired ApplicationContext context;

    @Test
    void paymentModuleSuppliesThePrimaryOrderPaymentGateway() {
        assertThat(context.getBean(OrderPaymentGateway.class)).isInstanceOf(PaymentOrderPaymentGateway.class);
        assertThat(context.getBeansOfType(OrderPaymentGateway.class)).hasSize(2); // order's no-op stub + ours
    }

    @Test
    void payosPlaceOrder_createsASignedPaymentLink_andAPendingPayment() throws Exception {
        Account chef = verifiedChef("whchef");
        Account customer = customerWithAddress("whcus");
        Dish dish = dish(chef, "Phở", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 2);

        assertThat(placed.response().get("payment_url").asString())
                .isEqualTo("https://pay.payos.test/web/link-" + placed.orderCode());
        assertThat(placed.response().get("qr_code").asString()).contains("QR-" + placed.orderCode());
        assertThat(placed.response().get("payment_uid").asString()).isEqualTo(placed.payment().getUid().toString());
        assertThat(placed.payment().getAmount()).isEqualByComparingTo("235000"); // 200k + 10% tax + 15k ship
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(eventsOf(placed.payment())).extracting(PaymentTransactionEvent::getEventType)
                .containsExactly("STATE_INIT", "PAYOS_CREATE");
        // the amount PayOS was asked for is the server-side checkout total, never client input
        assertThat(fakePayOs.requestsTo("/v2/payment-requests").getFirst().body()).contains("\"amount\":235000");
    }

    @Test
    void webhookEndpoint_isRawHttp200_withNoEnvelope_forAnyMethodAndAnyBody() throws Exception {
        mockMvc.perform(get("/api/payment/payos/webhook")).andExpect(status().isOk()).andExpect(content().string(""));
        mockMvc.perform(post("/api/payment/payos/webhook")).andExpect(status().isOk()).andExpect(content().string(""));
        mockMvc.perform(post("/api/payment/payos/webhook").contentType("application/json").content("{not json"))
                .andExpect(status().isOk()).andExpect(content().string(""));
        mockMvc.perform(post("/api/payment/payos/webhook").contentType("application/json").content("[1,2]"))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void forgedWebhook_changesNothing_butLeavesAForensicEventInTheAuditChain() throws Exception {
        Account chef = verifiedChef("fgchef");
        Account customer = customerWithAddress("fgcus");
        Dish dish = dish(chef, "Bún", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);

        postWebhook(webhookBody(placed.orderCode(), 1, true, null, false))
                .andExpect(status().isOk()).andExpect(content().string(""));

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reservationsOf(placed.orderUid())).allMatch(r -> r.getStatus() == StockReservationStatus.RESERVED);
        assertThat(committed(dish)).isEqualTo(5);
        PaymentTransactionEvent forensic = eventsOf(placed.payment()).getLast();
        assertThat(forensic.getEventType()).isEqualTo("WEBHOOK_SIG_INVALID");
        assertThat(forensic.getSignatureValid()).isFalse();
        assertThat((String) forensic.getPayload().get("truncated_raw")).startsWith("{'code': '00'");
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
    }

    /**
     * DJANGO BUG, reproduced only with the flag switched ON (PROGRESS.md payment open question #1,
     * fixed by default): a genuine, correctly signed
     * success webhook ends with the order CANCELLED and the customer refunded into the internal
     * wallet, because {@code _sync_successful_payment} compares {@code confirm()}'s bool against
     * strings. The first item's stock stays deducted (confirmed, never released); a second item's
     * hold is never confirmed at all.
     */
    @Test
    void djangoBugFlagOn_successWebhook_confirmsTheFirstItem_thenRefundsAndCancelsTheOrder() throws Exception {
        Account chef = verifiedChef("bugchef");
        Account customer = customerWithAddress("bugcus");
        Dish first = dish(chef, "Cơm", "100000", 5);
        Dish second = dish(chef, "Canh", "50000", 5);
        paymentProperties.setPreserveDjangoConfirmOutcomeBug(true); // reset to false by the base class
        Placed placed = placePayosOrder(customer, List.of(first, second), 1);
        Order order = reload(placed.orderUid());

        postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true))
                .andExpect(status().isOk());

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        Order after = reload(placed.orderUid());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        List<WalletTransaction> refunds = ledgerOf(customer, WalletTransactionType.REFUND);
        assertThat(refunds).singleElement().satisfies(t -> {
            assertThat(t.getAmount()).isEqualByComparingTo(order.getTotalPrice());
            assertThat(t.getReferenceId()).isEqualTo("refund_" + placed.orderUid());
        });
        assertThat(walletBalance(customer)).isEqualByComparingTo(order.getTotalPrice());
        // stock: the first item (lowest pk, Django's iteration order) confirmed and deducted —
        // and never released although the order is cancelled; the second never confirmed.
        var reservations = reservationsOf(placed.orderUid());
        assertThat(reservations.get(0).getStatus()).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(reservations.get(1).getStatus()).isEqualTo(StockReservationStatus.RESERVED);
        Dish confirmedDish = reservations.get(0).getDish().getUid().equals(first.getUid()) ? first : second;
        Dish heldDish = confirmedDish == first ? second : first;
        assertThat(committed(confirmedDish)).isEqualTo(4);
        assertThat(committed(heldDish)).isEqualTo(5);
        assertThat(eventsOf(placed.payment())).extracting(PaymentTransactionEvent::getEventType)
                .contains("PAYOS_WEBHOOK", "STATE_CHANGED");
        // Late-payment refund notification: outbox row committed together with the cancellation.
        assertThat(jdbc.queryForObject("SELECT payload->>'event_type' FROM outbox_event WHERE idempotency_key = ?",
                String.class, "order-late-payment-refunded:" + placed.orderUid()))
                .isEqualTo("CANCELLED_REFUNDED_LATE_PAYMENT");

        // Replaying the webhook (PayOS retries) must not refund again: REFUNDED is terminal.
        int eventsBefore = eventsOf(placed.payment()).size();
        postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true))
                .andExpect(status().isOk());
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).hasSize(1);
        assertThat(walletBalance(customer)).isEqualByComparingTo(order.getTotalPrice());
        assertThat(eventsOf(placed.payment())).hasSize(eventsBefore); // the guard writes nothing
    }

    /**
     * The DEFAULT configuration (open question #1 fixed): the same genuine success webhook leaves
     * the order CONFIRMED_SYSTEM with the money HOLDING in escrow, EVERY item's stock hold
     * confirmed, and nobody refunded.
     */
    @Test
    void defaultMode_successWebhook_holdsTheMoney_confirmsEveryItem_andRefundsNobody() throws Exception {
        assertThat(paymentProperties.isPreserveDjangoConfirmOutcomeBug()).isFalse(); // application.yml default
        Account chef = verifiedChef("okchef");
        Account customer = customerWithAddress("okcus");
        Dish first = dish(chef, "Cơm", "100000", 5);
        Dish second = dish(chef, "Canh", "50000", 5);
        Placed placed = placePayosOrder(customer, List.of(first, second), 1);
        Order order = reload(placed.orderUid());

        postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true))
                .andExpect(status().isOk());

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        Order after = reload(placed.orderUid());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(reservationsOf(placed.orderUid())).hasSize(2)
                .allMatch(r -> r.getStatus() == StockReservationStatus.CONFIRMED);
        assertThat(committed(first)).isEqualTo(4);
        assertThat(committed(second)).isEqualTo(4);
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).isEmpty();
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
        // The customer notification goes through the transactional outbox (backend-edit 9939179).
        assertThat(jdbc.queryForObject("SELECT payload->>'event_type' FROM outbox_event WHERE idempotency_key = ?",
                String.class, "order-confirmed:" + placed.orderUid())).isEqualTo("CONFIRMED");
    }

    /**
     * A valid FAILURE webhook: payment CANCELLED/FAILED, orders cancelled, vouchers cancelled.
     * The old Django stock-inflation bug ({@code _cancel_orders_for_payment} ADDED the ordered
     * quantity to DishAvailability although a PayOS order's stock was only held) was fixed in
     * the newer Django reference (backend-edit 9939179) and here: the hold is RELEASED through
     * the ledger, committed stock is untouched and the Redis counter gets its units back.
     */
    @Test
    void failureWebhook_cancelsPaymentAndOrders_andReleasesTheHoldWithoutInflatingStock() throws Exception {
        Account chef = verifiedChef("flchef");
        Account customer = customerWithAddress("flcus");
        Dish dish = dish(chef, "Gỏi", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 2);

        postWebhook(webhookBody(placed.orderCode(), 1, false, "CANCELLED", true)).andExpect(status().isOk());

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        Order after = reload(placed.orderUid());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(committed(dish)).isEqualTo(5); // never deducted, so nothing added back
        assertThat(reservationsOf(placed.orderUid())).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(com.amomeal.marketplace.dish.entity.StockReservationStatus.RELEASED));
        assertThat(redis.get(com.amomeal.marketplace.dish.config.StockRedisClient.stockKey(dish.getUid(), date)))
                .isEqualTo(5L); // 5 - 2 held + 2 released
        assertThat(walletBalance(customer)).isEqualByComparingTo("0"); // nothing was paid, nothing refunded

        // EXPIRED -> FAILED; and a later webhook for a terminal payment is ignored
        postWebhook(webhookBody(placed.orderCode(), 1, true, null, true)).andExpect(status().isOk());
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void unknownOrderCode_andNoSignature_areHarmless() throws Exception {
        postWebhook(webhookBody(424242L, 1, true, null, true)).andExpect(status().isOk());
        Map<String, Object> unsigned = webhookBody(424243L, 1, true, null, true);
        unsigned.remove("signature");
        postWebhook(unsigned).andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void returnEndpoint_isRawJson_notTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/payment/payos/return").param("code", "01").param("orderCode", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Payment cancelled"))
                .andExpect(jsonPath("$.order_code").value("123"))
                .andExpect(jsonPath("$.message_code").doesNotExist());
        mockMvc.perform(get("/api/payment/payos/return").param("code", "99"))
                .andExpect(jsonPath("$.message").value("Payment failed"))
                .andExpect(jsonPath("$.code").value("99"))
                .andExpect(jsonPath("$.order_code").isEmpty());
        mockMvc.perform(post("/api/payment/payos/return"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Invalid request method"));
        mockMvc.perform(get("/api/payment/payos/return").param("code", "00").param("orderCode", "not-a-number"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sync_result.error").value("Invalid order_code returned from PayOS"));
        mockMvc.perform(get("/api/payment/payos/return").param("code", "00").param("orderCode", "999"))
                .andExpect(jsonPath("$.sync_result.success").value(false))
                .andExpect(jsonPath("$.sync_result.error").value("Payment with order code 999 not found"));
    }

    @Test
    void returnEndpoint_onlyTrustsPayos_notTheQueryString() throws Exception {
        Account chef = verifiedChef("rtchef");
        Account customer = customerWithAddress("rtcus");
        Dish dish = dish(chef, "Chả", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);

        // browser claims success, PayOS still says PENDING -> nothing is confirmed
        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/payment/payos/return")
                        .param("code", "00").param("orderCode", String.valueOf(placed.orderCode())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(body.get("sync_result").get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("sync_result").get("gateway_status").asString()).isEqualTo("PENDING");
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void customerCanCancelAnUnpaidPayosOrder_linkCancelledAtPayos_noMoneyMoves() throws Exception {
        Account chef = verifiedChef("cxchef");
        Account customer = customerWithAddress("cxcus");
        Dish dish = dish(chef, "Xôi", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);

        call(post("/api/orders/" + placed.orderUid() + "/cancel"), customer).andExpect(status().isOk());

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(fakePayOs.requestsTo("/v2/payment-requests/" + placed.orderCode() + "/cancel")).hasSize(1);
        Order after = reload(placed.orderUid());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED); // synced by order.cancelOrder
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
        // refund_status now shows on the order (CANCELLED payment, no explicit refund_status -> PROCESSING)
        call(get("/api/orders/" + placed.orderUid()), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refund_status").value("PROCESSING"));
    }

    @Test
    void unauthenticated_paymentApi_is401_butCallbacksArePublic() throws Exception {
        mockMvc.perform(get("/api/payment/wallet/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payment/payos/return")).andExpect(status().isOk());
        Account customer = register("authcus", UserRole.CUSTOMER);
        call(get("/api/payment/wallet/me"), customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0))
                .andExpect(jsonPath("$.data.recent_transactions").isEmpty());
    }
}
