package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.dish.config.StockRedisClient;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.dish.service.StockReservationService;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.LedgerType;
import com.amomeal.marketplace.payment.entity.PaymentTransactionEvent;
import com.amomeal.marketplace.payment.entity.PayoutLedger;
import com.amomeal.marketplace.payment.entity.SettlementRecord;
import com.amomeal.marketplace.payment.entity.SettlementStatus;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.repository.PayoutLedgerRepository;
import com.amomeal.marketplace.payment.repository.SettlementRecordRepository;
import com.amomeal.marketplace.payment.repository.WalletTransactionStateRepository;
import com.amomeal.marketplace.payment.service.PaymentRefundService;
import com.amomeal.marketplace.payment.service.PaymentStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The escrow lifecycle as the Django code's comments INTEND it (flag
 * {@code preserveDjangoConfirmOutcomeBug=false}, the default since open question #1 was fixed;
 * Django's actual bug is pinned, flag on, by {@link PayOsWebhookTest}): PayOS webhook → HOLDING / CONFIRMED_SYSTEM → chef completes →
 * settlement + escrow RELEASE into the chef's wallet; or cancel → REFUND into the customer's
 * wallet; plus replay idempotency, concurrent-refund safety, the late-webhook recovery and
 * per-order escrow in multi-chef checkouts (open question #3).
 * Everything else (signing, state machine, ledger) is the same code in both modes.
 */
class PayOsEscrowLifecycleTest extends AbstractPaymentFullStackTest {

    @Autowired SettlementRecordRepository settlementRepository;
    @Autowired PayoutLedgerRepository ledgerRepository;
    @Autowired WalletTransactionStateRepository walletTxStateRepository;
    @Autowired PaymentRefundService refundService;
    @Autowired StockReservationService stockReservationService;
    @Autowired PaymentStateService stateService;

    @BeforeEach
    void intendedMode() {
        paymentProperties.setPreserveDjangoConfirmOutcomeBug(false);
    }

    private void paidWebhook(Placed placed, Order order) throws Exception {
        postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true))
                .andExpect(status().isOk());
    }

    @Test
    void fullLifecycle_webhookToEscrow_chefCompletes_releaseIntoChefWallet_replaysMoveNothing() throws Exception {
        Account chef = verifiedChef("lcchef");
        Account customer = customerWithAddress("lccus");
        Dish dish = dish(chef, "Lẩu", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 2);
        Order order = reload(placed.orderUid());
        assertThat(order.getTotalPrice()).isEqualByComparingTo("235000");

        // --- webhook: money recognized as held in escrow ---
        paidWebhook(placed, order);
        var state = stateOf(placed.payment());
        assertThat(state.getStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(state.getPaidAt()).isNotNull();
        assertThat(state.getTransactionId()).isEqualTo("FT" + placed.orderCode()); // PayOS reference
        Order confirmed = reload(placed.orderUid());
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
        assertThat(confirmed.getPaymentStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(reservationsOf(placed.orderUid())).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(committed(dish)).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> split = (Map<String, Object>) ((Map<String, Object>) state.getGatewayResponse().get("settlement"))
                .get("split_summary");
        assertThat(split).containsEntry("gross_amount", 235000.0).containsEntry("platform_fee_amount", 23500.0)
                .containsEntry("chef_payout_amount", 211500.0).containsEntry("chef_count", 1);
        assertThat(eventsOf(placed.payment())).filteredOn(e -> Boolean.TRUE.equals(e.getSignatureValid()))
                .extracting(PaymentTransactionEvent::getEventType).contains("PAYOS_WEBHOOK");
        assertThat(walletBalance(chef)).isEqualByComparingTo("0"); // escrow: the chef has nothing yet

        // --- replayed webhook (at-least-once delivery): nothing is written at all ---
        int events = eventsOf(placed.payment()).size();
        paidWebhook(placed, order);
        assertThat(eventsOf(placed.payment())).hasSize(events);
        assertThat(committed(dish)).isEqualTo(3);

        // --- chef lifecycle -> COMPLETED -> settlement + RELEASE ---
        call(post("/api/chef/orders/" + placed.orderUid() + "/confirm"), chef).andExpect(status().isOk());
        call(post("/api/chef/orders/" + placed.orderUid() + "/start-processing"), chef).andExpect(status().isOk());
        call(post("/api/chef/orders/" + placed.orderUid() + "/start-delivery"), chef).andExpect(status().isOk());
        call(post("/api/chef/orders/" + placed.orderUid() + "/complete"), chef).andExpect(status().isOk());

        SettlementRecord settlement = settlementRepository.findFirstByOrderUid(placed.orderUid()).orElseThrow();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.LEDGER_RECORDED);
        assertThat(settlement.getGrossAmount()).isEqualByComparingTo("235000");
        assertThat(settlement.getPlatformFee()).isEqualByComparingTo("23500");
        assertThat(settlement.getChefPayoutAmount()).isEqualByComparingTo("211500");
        List<PayoutLedger> ledger = ledgerRepository.findBySettlementRecordIdOrderByIdAsc(settlement.getId());
        assertThat(ledger).extracting(PayoutLedger::getLedgerType)
                .containsExactly(LedgerType.PLATFORM_REVENUE, LedgerType.CHEF_PAYOUT);
        // double entry balances: platform + chef == gross
        assertThat(ledger.get(0).getAmount().add(ledger.get(1).getAmount())).isEqualByComparingTo(settlement.getGrossAmount());

        List<WalletTransaction> releases = ledgerOf(chef, WalletTransactionType.RELEASE);
        assertThat(releases).singleElement().satisfies(t -> {
            assertThat(t.getAmount()).isEqualByComparingTo("211500");
            assertThat(t.getBalanceBefore()).isEqualByComparingTo("0");
            assertThat(t.getBalanceAfter()).isEqualByComparingTo("211500");
            assertThat(t.getOrderUid()).isEqualTo(placed.orderUid());
            assertThat(t.getPreviousHash()).isEqualTo(WalletTransaction.GENESIS_HASH);
        });
        assertThat(walletTxStateRepository.findByWalletTransactionId(releases.getFirst().getId()).orElseThrow().getStatus().name())
                .isEqualTo("SUCCESS");
        assertThat(walletBalance(chef)).isEqualByComparingTo("211500");
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.RELEASED);

        // --- a webhook replayed after release cannot pull the money back or re-credit anyone ---
        paidWebhook(placed, order);
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(ledgerOf(chef, WalletTransactionType.RELEASE)).hasSize(1);
        assertThat(walletBalance(chef)).isEqualByComparingTo("211500");
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");

        // chef sees it in /wallet/me
        JsonNode wallet = data(call(get("/api/payment/wallet/me"), chef).andExpect(status().isOk()));
        assertThat(wallet.get("balance").decimalValue()).isEqualByComparingTo("211500");
        assertThat(wallet.get("recent_transactions").get(0).get("transaction_type").asString()).isEqualTo("RELEASE");
        assertThat(wallet.get("recent_transactions").get(0).get("status").asString()).isEqualTo("SUCCESS");
    }

    @Test
    void cancelAfterPayment_refundsTheCustomerWallet_once_evenUnderConcurrentRefundAttempts() throws Exception {
        Account chef = verifiedChef("rfchef");
        Account customer = customerWithAddress("rfcus");
        Dish dish = dish(chef, "Bò", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);
        Order order = reload(placed.orderUid());
        paidWebhook(placed, order);
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);

        // 8 concurrent refund attempts for the same order (double-click, retries, chef+customer)
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Map<String, Object>> task = () -> {
                start.await();
                return refundService.handleOrderCancellationRefund(placed.orderUid(), "race");
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int refunded = 0;
        int skipped = 0;
        for (Future<Map<String, Object>> f : results) {
            Map<String, Object> r = f.get();
            assertThat(r).containsEntry("success", true);
            if ("REFUNDED".equals(r.get("refund_status"))) {
                refunded++;
            } else if ("ALREADY_REFUNDED".equals(r.get("refund_status"))) {
                skipped++;
            }
        }
        pool.shutdown();
        assertThat(refunded).isEqualTo(1);
        assertThat(skipped).isEqualTo(threads - 1);
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).hasSize(1);
        assertThat(walletBalance(customer)).isEqualByComparingTo(order.getTotalPrice());
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // the actual customer cancel afterwards: refund skipped, stock restored, still one credit
        call(post("/api/orders/" + placed.orderUid() + "/cancel"), customer).andExpect(status().isOk());
        Order after = reload(placed.orderUid());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reservationsOf(placed.orderUid())).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.CANCELLED);
        assertThat(committed(dish)).isEqualTo(5);
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).hasSize(1);
        assertThat(walletBalance(chef)).isEqualByComparingTo("0");
    }

    @Test
    void refund_isRefused_whenTheAuditChainWasTampered() throws Exception {
        Account chef = verifiedChef("tpchef");
        Account customer = customerWithAddress("tpcus");
        Dish dish = dish(chef, "Mực", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);
        paidWebhook(placed, reload(placed.orderUid()));
        // someone edits an audit event directly in the database
        jdbc.update("UPDATE payment_transaction_events SET status_to = 'SUCCESS' WHERE payment_transaction_id = ? "
                + "AND event_type = 'STATE_INIT'", placed.payment().getId());

        Map<String, Object> r = refundService.handleOrderCancellationRefund(placed.orderUid(), "x");
        assertThat(r).containsEntry("success", false);
        assertThat((String) r.get("error")).contains("audit chain tampered");
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING); // rolled back whole
    }

    @Test
    void returnUrlSync_confirmsWhenPayosSaysPaid_recordingReconciliationEvidence() throws Exception {
        Account chef = verifiedChef("syncchef");
        Account customer = customerWithAddress("synccus");
        Dish dish = dish(chef, "Nem", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);
        fakePayOs.setLinkStatus(placed.orderCode(), "PAID");

        String json = mockMvc.perform(get("/api/payment/payos/return").param("code", "00")
                        .param("orderCode", String.valueOf(placed.orderCode())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(json);
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("sync_result").get("status").asString()).isEqualTo("HOLDING");
        assertThat(body.get("sync_result").get("orders_count").asInt()).isEqualTo(1);
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
        assertThat(eventsOf(placed.payment())).extracting(PaymentTransactionEvent::getEventType)
                .contains("RECONCILIATION_CONFIRMED");

        // the late real webhook is then a no-op
        int events = eventsOf(placed.payment()).size();
        paidWebhook(placed, reload(placed.orderUid()));
        assertThat(eventsOf(placed.payment())).hasSize(events);

        // RECONCILIATION evidence is enough to authorize a refund later
        call(post("/api/orders/" + placed.orderUid() + "/cancel"), customer).andExpect(status().isOk());
        assertThat(walletBalance(customer)).isEqualByComparingTo(reload(placed.orderUid()).getTotalPrice());
    }

    @Test
    void returnUrlReplay_afterConfirmation_isIdempotent_andDoesNotResetAnAdvancedOrder() throws Exception {
        // Post-port fix (2026-09-25): Django re-applied the confirmation on every replay of
        // /payos/return, moving orders a chef had already advanced back to CONFIRMED_SYSTEM.
        Account chef = verifiedChef("replaychef");
        Account customer = customerWithAddress("replaycus");
        Dish dish = dish(chef, "Bun", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 1);
        fakePayOs.setLinkStatus(placed.orderCode(), "PAID");
        mockMvc.perform(get("/api/payment/payos/return").param("code", "00")
                .param("orderCode", String.valueOf(placed.orderCode()))).andExpect(status().isOk());
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);

        orderRepository.updateStatus(placed.orderUid(), OrderStatus.CONFIRMED_SHOP); // chef accepted
        int events = eventsOf(placed.payment()).size();
        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/payment/payos/return").param("code", "00")
                        .param("orderCode", String.valueOf(placed.orderCode())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(body.get("sync_result").get("message").asString()).isEqualTo("Already processed");
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SHOP);
        assertThat(eventsOf(placed.payment())).hasSize(events);
    }

    @Test
    void lateWebhook_afterTheHoldExpired_reReservesAndConfirms_whenStockIsStillThere() throws Exception {
        Account chef = verifiedChef("latechef");
        Account customer = customerWithAddress("latecus");
        Dish dish = dish(chef, "Cá", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), 2);
        expireHoldsAndSweepStockOnly(placed);
        assertThat(reservationsOf(placed.orderUid())).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.EXPIRED);

        paidWebhook(placed, reload(placed.orderUid()));

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
        assertThat(reservationsOf(placed.orderUid())).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(committed(dish)).isEqualTo(3);
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
    }

    @Test
    void lateWebhook_afterTheItemSoldOut_refundsTheCustomer_andCancelsTheOrder() throws Exception {
        Account chef = verifiedChef("sochef");
        Account customer = customerWithAddress("socus");
        Dish dish = dish(chef, "Tôm", "100000", 2);
        Placed placed = placePayosOrder(customer, List.of(dish), 2);
        expireHoldsAndSweepStockOnly(placed);
        // meanwhile the stock was sold elsewhere
        DishAvailability availability = availabilityRepository.findByDishAndAvailableDate(dish, date).orElseThrow();
        availability.setAvailableQuantity(0);
        availabilityRepository.save(availability);
        redis.delete(StockRedisClient.stockKey(dish.getUid(), date));

        Order order = reload(placed.orderUid());
        paidWebhook(placed, order);

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo(order.getTotalPrice()));
        assertThat(committed(dish)).isZero(); // nothing deducted for the unfulfillable order
        assertThat(reservationsOf(placed.orderUid())).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.EXPIRED);
    }

    /** Chef of {@code o}, among the given accounts. */
    private static Account chefOf(Order o, Account... chefs) {
        for (Account a : chefs) {
            if (o.getChef().getId().equals(a.user().getId())) {
                return a;
            }
        }
        throw new IllegalStateException("no chef for order " + o.getUid());
    }

    private void completeAsChef(Order o, Account chef) throws Exception {
        for (String step : List.of("confirm", "start-processing", "start-delivery", "complete")) {
            call(post("/api/chef/orders/" + o.getUid() + "/" + step), chef).andExpect(status().isOk());
        }
    }

    /** A paid (HOLDING) 2-chef checkout: one PayOS payment of 280000 covering two 140000 orders. */
    private Placed paidTwoChefCheckout(Account chefA, Account chefB, Account customer) throws Exception {
        Dish dishA = dish(chefA, "A", "100000", 5);
        Dish dishB = dish(chefB, "B", "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dishA, dishB), 1);
        assertThat(orderRepository.findDetailedByCheckoutUid(placed.checkoutUid())).hasSize(2)
                .allSatisfy(o -> assertThat(o.getTotalPrice()).isEqualByComparingTo("140000")); // 100k + 10k + 30k
        assertThat(placed.payment().getAmount()).isEqualByComparingTo("280000");
        postWebhook(webhookBody(placed.orderCode(), 280000, true, null, true)).andExpect(status().isOk());
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        return placed;
    }

    /**
     * FIXED (PROGRESS.md payment open question #3): one PayOS payment covers every order of a
     * checkout; escrow is now decided PER ORDER from the wallet ledger. In Django only the first
     * completed order's chef was ever paid (the first completion set the one payment RELEASED).
     */
    @Test
    void multiChefCheckout_bothChefsComplete_eachIsReleasedTheir90PercentShare() throws Exception {
        Account chefA = verifiedChef("mcA");
        Account chefB = verifiedChef("mcB");
        Account customer = customerWithAddress("mccus");
        Placed placed = paidTwoChefCheckout(chefA, chefB, customer);
        List<Order> orders = orderRepository.findDetailedByCheckoutUid(placed.checkoutUid());

        completeAsChef(orders.get(0), chefOf(orders.get(0), chefA, chefB));
        // first order released; the other order's money is still in escrow
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(eventsOf(placed.payment())).extracting(PaymentTransactionEvent::getEventType).contains("ORDER_RELEASED");

        completeAsChef(orders.get(1), chefOf(orders.get(1), chefA, chefB));
        assertThat(walletBalance(chefA)).isEqualByComparingTo("126000"); // 90% of 140000
        assertThat(walletBalance(chefB)).isEqualByComparingTo("126000");
        for (Order o : orders) {
            Account chef = chefOf(o, chefA, chefB);
            assertThat(ledgerOf(chef, WalletTransactionType.RELEASE)).singleElement().satisfies(t -> {
                assertThat(t.getOrderUid()).isEqualTo(o.getUid());
                assertThat(t.getAmount()).isEqualByComparingTo(
                        settlementRepository.findFirstByOrderUid(o.getUid()).orElseThrow().getChefPayoutAmount());
            });
        }
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.RELEASED); // last order left escrow
        assertThat(stateService.verifyEventChain(placed.payment().getId()).valid()).isTrue();

        // replays move nothing: webhook after release, and a refund attempt for a released order
        postWebhook(webhookBody(placed.orderCode(), 280000, true, null, true)).andExpect(status().isOk());
        assertThat(refundService.handleOrderCancellationRefund(orders.get(0).getUid(), "late")).containsEntry("success", false);
        assertThat(walletBalance(chefA)).isEqualByComparingTo("126000");
        assertThat(walletBalance(chefB)).isEqualByComparingTo("126000");
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
    }

    /**
     * FIXED (open question #3): cancelling one order of a multi-chef checkout refunds exactly that
     * order's total, once, and leaves the sibling in escrow — still releasable to its chef. In
     * Django the first refund marked the whole payment REFUNDED (so the sibling could never be
     * released, and a second cancelled order was never refunded).
     */
    @Test
    void multiChefCheckout_cancelOne_refundsOnlyThatOrder_theOtherIsStillReleasable() throws Exception {
        Account chefA = verifiedChef("mxA");
        Account chefB = verifiedChef("mxB");
        Account customer = customerWithAddress("mxcus");
        Placed placed = paidTwoChefCheckout(chefA, chefB, customer);
        List<Order> orders = orderRepository.findDetailedByCheckoutUid(placed.checkoutUid());
        Order cancelled = orders.get(0);
        Order kept = orders.get(1);

        call(post("/api/orders/" + cancelled.getUid() + "/cancel"), customer).andExpect(status().isOk());
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).singleElement().satisfies(t -> {
            assertThat(t.getOrderUid()).isEqualTo(cancelled.getUid());
            assertThat(t.getAmount()).isEqualByComparingTo("140000"); // only that order's total
        });
        assertThat(walletBalance(customer)).isEqualByComparingTo("140000");
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(eventsOf(placed.payment())).extracting(PaymentTransactionEvent::getEventType).contains("ORDER_REFUNDED");
        assertThat(reload(cancelled.getUid()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reload(kept.getUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);

        // retried refunds of the cancelled order are exactly-once
        assertThat(refundService.handleOrderCancellationRefund(cancelled.getUid(), "retry"))
                .containsEntry("refund_status", "ALREADY_REFUNDED");
        assertThat(walletBalance(customer)).isEqualByComparingTo("140000");

        // the sibling is still releasable: its chef gets 90% of ITS order
        Account keptChef = chefOf(kept, chefA, chefB);
        Account cancelledChef = chefOf(cancelled, chefA, chefB);
        completeAsChef(kept, keptChef);
        assertThat(walletBalance(keptChef)).isEqualByComparingTo("126000");
        assertThat(walletBalance(cancelledChef)).isEqualByComparingTo("0");
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.RELEASED); // nothing left in escrow
        assertThat(walletBalance(customer)).isEqualByComparingTo("140000");
        assertThat(stateService.verifyEventChain(placed.payment().getId()).valid()).isTrue();
    }

    /**
     * FIXED (open question #3): both orders cancelled, with concurrent duplicate refund attempts
     * per order — each order is refunded exactly once (2 credits, the full 280000), then the
     * payment is REFUNDED.
     */
    @Test
    void multiChefCheckout_cancelBoth_concurrently_eachOrderRefundedExactlyOnce() throws Exception {
        Account chefA = verifiedChef("mbA");
        Account chefB = verifiedChef("mbB");
        Account customer = customerWithAddress("mbcus");
        Placed placed = paidTwoChefCheckout(chefA, chefB, customer);
        List<Order> orders = orderRepository.findDetailedByCheckoutUid(placed.checkoutUid());

        int perOrder = 4;
        ExecutorService pool = Executors.newFixedThreadPool(perOrder * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> results = new ArrayList<>();
        for (int i = 0; i < perOrder; i++) {
            for (Order o : orders) {
                Callable<Map<String, Object>> task = () -> {
                    start.await();
                    return refundService.handleOrderCancellationRefund(o.getUid(), "race");
                };
                results.add(pool.submit(task));
            }
        }
        start.countDown();
        int refunded = 0;
        for (Future<Map<String, Object>> f : results) {
            Map<String, Object> r = f.get();
            assertThat(r).containsEntry("success", true);
            if ("REFUNDED".equals(r.get("refund_status"))) {
                refunded++;
            }
        }
        pool.shutdown();
        assertThat(refunded).isEqualTo(2);
        assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).hasSize(2)
                .extracting(WalletTransaction::getOrderUid)
                .containsExactlyInAnyOrderElementsOf(orders.stream().map(Order::getUid).toList());
        assertThat(walletBalance(customer)).isEqualByComparingTo("280000");
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // the actual cancels afterwards: no further credit
        for (Order o : orders) {
            call(post("/api/orders/" + o.getUid() + "/cancel"), customer).andExpect(status().isOk());
            assertThat(reload(o.getUid()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
        assertThat(walletBalance(customer)).isEqualByComparingTo("280000");
        assertThat(walletBalance(chefA)).isEqualByComparingTo("0");
        assertThat(walletBalance(chefB)).isEqualByComparingTo("0");
        assertThat(stateService.verifyEventChain(placed.payment().getId()).valid()).isTrue();
    }

    /** What the 30 s TTL sweep does to the stock side (without order's own cancellation step). */
    private void expireHoldsAndSweepStockOnly(Placed placed) {
        for (StockReservation r : reservationsOf(placed.orderUid())) {
            r.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            reservationRepository.save(r);
        }
        stockReservationService.releaseExpiredHolds();
    }

    @Test
    void codOrder_completion_marksPaymentSuccess_withoutTouchingAnyWallet() throws Exception {
        Account chef = verifiedChef("codchef");
        Account customer = customerWithAddress("codcus");
        Dish dish = dish(chef, "Bánh", "100000", 5);
        selectInCart(customer, dish, 1);
        JsonNode checkout = data(call(post("/api/checkouts/"), customer).andExpect(status().isOk()));
        String co = checkout.get("uid").asString();
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        var payment = paymentRepository.findByCheckoutUid(java.util.UUID.fromString(co)).orElseThrow();
        assertThat(stateOf(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stateOf(payment).getTransactionId()).isEqualTo("COD-" + co);
        String orderUid = checkout.get("orders").get(0).get("uid").asString();

        call(post("/api/chef/orders/" + orderUid + "/confirm"), chef).andExpect(status().isOk());
        call(post("/api/chef/orders/" + orderUid + "/start-processing"), chef).andExpect(status().isOk());
        call(post("/api/chef/orders/" + orderUid + "/start-delivery"), chef).andExpect(status().isOk());
        call(post("/api/chef/orders/" + orderUid + "/complete"), chef).andExpect(status().isOk());

        assertThat(stateOf(payment).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        SettlementRecord settlement = settlementRepository.findFirstByOrderUid(java.util.UUID.fromString(orderUid)).orElseThrow();
        assertThat(settlement.getPaymentMethod().name()).isEqualTo("COD");
        // 100k + 10k tax + 30k ship = 140k gross; 10% fee = 14k; chef 126k
        assertThat(settlement.getGrossAmount()).isEqualByComparingTo(new BigDecimal("140000"));
        assertThat(settlement.getChefPayoutAmount()).isEqualByComparingTo(new BigDecimal("126000"));
        assertThat(walletBalance(chef)).isEqualByComparingTo("0"); // COD never flows through the platform
        assertThat(walletBalance(customer)).isEqualByComparingTo("0");
    }
}
