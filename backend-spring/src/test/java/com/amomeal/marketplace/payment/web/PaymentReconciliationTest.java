package com.amomeal.marketplace.payment.web;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.PaymentTransactionEvent;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.service.PaymentReconciliationScheduler;
import com.amomeal.marketplace.payment.service.PaymentReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled PayOS reconciliation job, full stack (real Postgres/Redis, {@code FakePayOsServer}
 * over HTTP). The timer bean is off in tests (src/test/resources/application.properties); ticks
 * are driven by calling {@link PaymentReconciliationService#reconcileOnce()} directly.
 */
class PaymentReconciliationTest extends AbstractPaymentFullStackTest {

    @Autowired PaymentReconciliationService reconciliation;
    @Autowired ApplicationContext context;

    private Dish lastDish;
    private Account lastCustomer;

    /** Payments left PENDING by other tests must not fall into this test's age window. */
    @BeforeEach
    void freshenLeftoverPayments() {
        jdbc.update("UPDATE payment_transactions SET created_at = now() WHERE created_at < now() - interval '1 minute'");
    }

    private void age(Placed placed, String interval) {
        jdbc.update("UPDATE payment_transactions SET created_at = now() - interval '" + interval + "' WHERE id = ?",
                placed.payment().getId());
    }

    private Placed pending(String prefix, int qty) throws Exception {
        Account chef = verifiedChef(prefix + "chef");
        Account customer = customerWithAddress(prefix + "cus");
        Dish dish = dish(chef, "Mon " + prefix, "100000", 5);
        Placed placed = placePayosOrder(customer, List.of(dish), qty);
        lastDish = dish;
        lastCustomer = customer;
        return placed;
    }

    @Test
    void timerBeanIsOffInTests() {
        assertThat(context.getBeansOfType(PaymentReconciliationScheduler.class)).isEmpty();
    }

    @Test
    void oldEnoughPendingPayment_paidAtPayos_endsExactlyLikeTheWebhookPath() throws Exception {
        Placed placed = pending("rc1", 1);
        Dish dish = lastDish;
        age(placed, "5 minutes");
        fakePayOs.setLinkStatus(placed.orderCode(), "PAID");

        PaymentReconciliationService.Result result = reconciliation.reconcileOnce();

        assertThat(result.examined()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        Order after = reload(placed.orderUid());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(reservationsOf(placed.orderUid())).hasSize(1)
                .allMatch(r -> r.getStatus() == StockReservationStatus.CONFIRMED);
        assertThat(committed(dish)).isEqualTo(4);
        assertThat(eventsOf(placed.payment())).extracting(PaymentTransactionEvent::getEventType)
                .contains("RECONCILIATION_CONFIRMED");
        assertThat(ledgerOf(lastCustomer, WalletTransactionType.REFUND)).isEmpty();

        // next tick: no longer PENDING -> not a candidate, and nothing changes
        int events = eventsOf(placed.payment()).size();
        assertThat(reconciliation.reconcileOnce().examined()).isZero();
        assertThat(eventsOf(placed.payment())).hasSize(events);
    }

    @Test
    void tooYoungPayment_isNotTouched_andNeitherIsOneBeyondMaxAge() throws Exception {
        Placed young = pending("rc2", 1);
        fakePayOs.setLinkStatus(young.orderCode(), "PAID");
        Placed ancient = pending("rc2b", 1);
        age(ancient, "30 hours");
        fakePayOs.setLinkStatus(ancient.orderCode(), "PAID");
        int before = fakePayOs.requestsTo("/v2/payment-requests/").size();

        PaymentReconciliationService.Result result = reconciliation.reconcileOnce();

        assertThat(result.examined()).isZero();
        assertThat(fakePayOs.requestsTo("/v2/payment-requests/")).hasSize(before); // PayOS never asked
        assertThat(stateOf(young.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stateOf(ancient.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reload(young.orderUid()).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void stillPendingAtPayos_isLeftPending() throws Exception {
        Placed placed = pending("rc3", 1);
        age(placed, "10 minutes");

        assertThat(reconciliation.reconcileOnce().examined()).isEqualTo(1);

        assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reload(placed.orderUid()).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancelledAndExpiredAtPayos_areHandledLikeTheExistingSync() throws Exception {
        Placed cancelled = pending("rc4", 2);
        Dish cancelledDish = lastDish;
        Placed expired = pending("rc4b", 1);
        age(cancelled, "10 minutes");
        age(expired, "10 minutes");
        fakePayOs.setLinkStatus(cancelled.orderCode(), "CANCELLED");
        fakePayOs.setLinkStatus(expired.orderCode(), "EXPIRED");

        assertThat(reconciliation.reconcileOnce().examined()).isEqualTo(2);

        assertThat(stateOf(cancelled.payment()).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(stateOf(expired.payment()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload(cancelled.orderUid()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reload(expired.orderUid()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // the preserved Django stock-inflation quirk, identical to the webhook path
        assertThat(committed(cancelledDish)).isEqualTo(7);
    }

    @Test
    void payosErrorForOnePayment_doesNotStopTheOthers() throws Exception {
        Placed broken = pending("rc5", 1);
        Placed good = pending("rc5b", 1);
        age(broken, "10 minutes");
        age(good, "9 minutes");
        fakePayOs.setLinkStatus(good.orderCode(), "PAID");
        fakePayOs.setFailLookup(broken.orderCode(), true);

        PaymentReconciliationService.Result result = reconciliation.reconcileOnce();

        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(stateOf(broken.payment()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stateOf(good.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
        assertThat(reload(good.orderUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
    }

    /** The webhook and a job tick race for the same PENDING payment: exactly one confirmation. */
    @Test
    void jobTickConcurrentWithTheWebhook_confirmsExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 4; i++) {
                Placed placed = pending("rc6x" + i, 1);
                Dish dish = lastDish;
                Account customer = lastCustomer;
                age(placed, "5 minutes");
                fakePayOs.setLinkStatus(placed.orderCode(), "PAID");
                Order order = reload(placed.orderUid());

                CountDownLatch go = new CountDownLatch(1);
                Future<?> webhook = pool.submit(() -> {
                    go.await();
                    postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true));
                    return null;
                });
                Future<?> tick = pool.submit(() -> {
                    go.await();
                    return reconciliation.reconcileOnce();
                });
                go.countDown();
                webhook.get(30, TimeUnit.SECONDS);
                tick.get(30, TimeUnit.SECONDS);

                assertThat(stateOf(placed.payment()).getStatus()).isEqualTo(PaymentStatus.HOLDING);
                Order after = reload(placed.orderUid());
                assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
                assertThat(reservationsOf(placed.orderUid())).hasSize(1)
                        .allMatch(r -> r.getStatus() == StockReservationStatus.CONFIRMED);
                assertThat(committed(dish)).isEqualTo(4); // deducted once, not twice
                assertThat(ledgerOf(customer, WalletTransactionType.REFUND)).isEmpty();
                assertThat(walletBalance(customer)).isEqualByComparingTo("0");
                assertThat(eventsOf(placed.payment()).stream()
                        .filter(e -> "STATE_CHANGED".equals(e.getEventType()) && "HOLDING".equals(e.getStatusTo())).count())
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** A tick after the webhook already confirmed must not re-apply anything. */
    @Test
    void tickAfterWebhookHasConfirmed_isANoOp() throws Exception {
        Placed placed = pending("rc7", 1);
        age(placed, "5 minutes");
        Order order = reload(placed.orderUid());
        postWebhook(webhookBody(placed.orderCode(), order.getTotalPrice().longValue(), true, null, true));
        fakePayOs.setLinkStatus(placed.orderCode(), "PAID");
        int events = eventsOf(placed.payment()).size();

        assertThat(reconciliation.reconcileOnce().examined()).isZero(); // HOLDING is not a candidate

        assertThat(eventsOf(placed.payment())).hasSize(events);
        assertThat(committed(lastDish)).isEqualTo(4);
    }
}
