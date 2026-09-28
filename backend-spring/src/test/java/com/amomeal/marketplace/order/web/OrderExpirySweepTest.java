package com.amomeal.marketplace.order.web;

import com.amomeal.marketplace.dish.config.StockProperties;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.dish.service.ExpiredOrderHandler;
import com.amomeal.marketplace.dish.service.StockHoldSweepScheduler;
import com.amomeal.marketplace.dish.service.StockReservationService;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.repository.NotificationIdempotencyKeyRepository;
import com.amomeal.marketplace.order.service.OrderExpiredOrderHandler;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import com.amomeal.marketplace.voucher.repository.AppliedVoucherRepository;
import com.amomeal.marketplace.voucher.repository.VoucherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The TTL expiry sweep (Django {@code dish/tasks.py::release_expired_stock_holds})
 * end to end through the real {@link StockHoldSweepScheduler#sweepOnce()} — dish's
 * step 1 (ledger RESERVED-&gt;EXPIRED + Redis credit) followed by this module's
 * {@link OrderExpiredOrderHandler} steps 2-3 (order -&gt; CANCELLED, vouchers -&gt;
 * EXPIRED, idempotent CANCELLED_EXPIRED notification).
 */
class OrderExpirySweepTest extends AbstractOrderFullStackTest {

    @Autowired StockReservationService stockReservationService;
    @Autowired ExpiredOrderHandler expiredOrderHandler;
    @Autowired StockProperties stockProperties;
    @Autowired NotificationIdempotencyKeyRepository keyRepository;
    @Autowired com.amomeal.marketplace.order.repository.OutboxEventRepository outboxRepository;
    @Autowired VoucherRepository voucherRepository;
    @Autowired AppliedVoucherRepository appliedVoucherRepository;
    @Autowired ApplicationContext context;

    /**
     * The {@code @Scheduled} bean is disabled in these tests ({@code app.stock.sweep-enabled=false})
     * so the clock cannot race the assertions; the same class is built by hand with the REAL
     * injected {@link ExpiredOrderHandler} (i.e. whatever bean wins — this module's) and driven
     * one tick at a time, like dish's own StockHoldSweepSchedulerTest does.
     */
    private StockHoldSweepScheduler sweep() {
        return new StockHoldSweepScheduler(stockReservationService, expiredOrderHandler, stockProperties);
    }

    /** Place a PAYOS order (holds stay RESERVED) and back-date its holds past the TTL. */
    private Order unpaidExpiredPayosOrder(Account chef, Account customer, Dish dish, int qty) throws Exception {
        selectInCart(customer, dish, qty);
        UUID co = checkout(customer);
        verifyChefBankIfNeeded(chef);
        call(patch("/api/checkouts/" + co + "/payment-method").contentType("application/json").content("\"PAYOS\""), customer)
                .andExpect(status().isOk());
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        Order order = onlyOrder(co);
        for (StockReservation r : reservationsOf(order)) {
            r.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            reservationRepository.save(r);
        }
        return order;
    }

    private void verifyChefBankIfNeeded(Account chef) {
        if (chefPaymentInfoRepository.findByUser(chef.user()).isEmpty()) {
            verifyChefBank(chef);
        }
    }

    private boolean awaitKey(String key) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (keyRepository.existsByKey(key)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void orderSeamIsTheActiveExpiredOrderHandler() {
        assertThat(expiredOrderHandler).isInstanceOf(OrderExpiredOrderHandler.class);
        assertThat(context.getBeansOfType(ExpiredOrderHandler.class)).hasSize(2); // dish's stub + ours (@Primary)
    }

    @Test
    void expiredUnpaidOrder_isCancelled_stockCreditedBack_vouchersExpired_andCustomerNotifiedOnce() throws Exception {
        Account chef = chef("sweepchef");
        Account customer = customerWithAddress("sweepcus");
        Dish dish = dish(chef, "Hết hạn", "250000", 4);
        Voucher shop = voucherRepository.save(Voucher.builder().chef(chef.user())
                .code(("S" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase()).name("s")
                .voucherType(VoucherType.SHOP_VOUCHER).discountType(VoucherDiscountType.FIXED_AMOUNT)
                .discountValue(new BigDecimal("10000")).startDate(Instant.now().minus(1, ChronoUnit.DAYS))
                .endDate(Instant.now().plus(1, ChronoUnit.DAYS)).usageLimit(10).usageLimitPerUser(5).build());

        selectInCart(customer, dish, 3);
        UUID co = checkout(customer);
        UUID orderUid = onlyOrder(co).getUid();
        call(post("/api/orders/" + orderUid + "/apply-voucher").contentType("application/json")
                .content("{\"voucher_code\":\"" + shop.getCode() + "\"}"), customer).andExpect(status().isOk());
        verifyChefBank(chef);
        call(patch("/api/checkouts/" + co + "/payment-method").contentType("application/json").content("\"PAYOS\""), customer)
                .andExpect(status().isOk());
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        assertThat(redisCounter(dish)).isEqualTo(1L);
        for (StockReservation r : reservationsOf(onlyOrder(co))) {
            r.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            reservationRepository.save(r);
        }

        int cancelled = sweep().sweepOnce();

        assertThat(cancelled).isGreaterThanOrEqualTo(1);
        Order order = reload(orderUid);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reservationsOf(order)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.EXPIRED);
        assertThat(redisCounter(dish)).isEqualTo(4L);   // held units credited back
        assertThat(committed(dish)).isEqualTo(4);        // never deducted, nothing to restore
        assertThat(appliedVoucherRepository.findAll().stream().filter(a -> orderUid.equals(a.getOrderUid())))
                .singleElement().extracting(AppliedVoucher::getStatus).isEqualTo(VoucherReservationStatus.EXPIRED);
        assertThat(awaitKey("order-expired:" + orderUid)).isTrue();
        // backend-edit 9939179: routed through the transactional outbox, not a direct async call.
        assertThat(outboxRepository.findByIdempotencyKey("order-expired:" + orderUid)).get()
                .satisfies(e -> assertThat(e.getPayload()).containsEntry("event_type", "CANCELLED_EXPIRED"));

        // A second tick (or a redelivered handler call) changes nothing and re-notifies nobody.
        assertThat(expiredOrderHandler.cancelExpiredOrders(Set.of(orderUid))).isZero();
        assertThat(redisCounter(dish)).isEqualTo(4L);
    }

    @Test
    void sweep_neverCancelsAnOrderThatWasAlreadyPaid() throws Exception {
        Account chef = chef("paidchef");
        Account customer = customerWithAddress("paidcus");
        Dish dish = dish(chef, "Đã trả", "250000", 4);
        Order order = unpaidExpiredPayosOrder(chef, customer, dish, 2);
        // The payment webhook (payment module) won the race: order already CONFIRMED_SYSTEM.
        orderRepository.updateStatus(order.getUid(), OrderStatus.CONFIRMED_SYSTEM);

        assertThat(expiredOrderHandler.cancelExpiredOrders(Set.of(order.getUid()))).isZero();
        assertThat(reload(order.getUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
        assertThat(keyRepository.existsByKey("order-expired:" + order.getUid())).isFalse();
        assertThat(outboxRepository.findByIdempotencyKey("order-expired:" + order.getUid())).isEmpty();
    }

    @Test
    void sweepLosingTheRaceToConfirm_backsOff_andTheOrderSurvives() throws Exception {
        Account chef = chef("racechef");
        Account customer = customerWithAddress("racecus");
        Dish dish = dish(chef, "Đua", "250000", 4);
        Order order = unpaidExpiredPayosOrder(chef, customer, dish, 2);
        // confirm() (the webhook) lands first, then the order moves on; the sweep's
        // candidate row is no longer RESERVED so release(expired=true) must back off.
        for (var item : order.getItems()) {
            assertThat(stockReservationService.confirm(item.getId(), dish, date, item.getQuantity())).isTrue();
        }
        orderRepository.updateStatus(order.getUid(), OrderStatus.CONFIRMED_SYSTEM);

        sweep().sweepOnce();

        assertThat(reservationsOf(reload(order.getUid()))).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(committed(dish)).isEqualTo(2);  // the sale stands, no refund of stock
        assertThat(reload(order.getUid()).getStatus()).isEqualTo(OrderStatus.CONFIRMED_SYSTEM);
    }

    @Test
    void codOrders_areNeverSwept_theirHoldsAreConfirmedAtPlaceOrder() throws Exception {
        Account chef = chef("codsweepchef");
        Account customer = customerWithAddress("codsweepcus");
        Dish dish = dish(chef, "COD", "50000", 4);
        selectInCart(customer, dish, 1);
        UUID co = checkout(customer);
        call(post("/api/checkouts/" + co + "/place-order"), customer).andExpect(status().isOk());
        Order order = onlyOrder(co);
        for (StockReservation r : reservationsOf(order)) {
            r.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            reservationRepository.save(r);
        }
        sweep().sweepOnce();
        assertThat(reload(order.getUid()).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(committed(dish)).isEqualTo(3);
    }
}
