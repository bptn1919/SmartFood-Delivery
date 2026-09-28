package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link OrderPaymentGateway} for as long as {@code payment} is not
 * ported: no PayOS session, no wallet, no escrow, no settlement.
 *
 * <p>It deliberately <b>succeeds</b> rather than throwing, so the surrounding
 * order lifecycle stays exercisable end to end:
 * <ul>
 *   <li>{@link #createCodPayment} / {@link #createPayosPayment} return an empty
 *       session — {@code place_order} proceeds exactly as it does in Django when
 *       the gateway call succeeds (COD confirms its stock holds immediately,
 *       PayOS leaves them RESERVED for the TTL sweep). Only {@code payment_url}/
 *       {@code qr_code} come back null, because there is nothing to link to.</li>
 *   <li>{@link #handleOrderCancellationRefund} reports {@code success=false} with
 *       a reason — which is exactly the branch Django already handles by logging
 *       a warning and cancelling anyway, so cancellation behavior is unchanged.</li>
 *   <li>{@link #settleCompletedOrder} moves no money and reports no new payment
 *       status, so {@code complete_order_with_release} degrades to "mark the order
 *       COMPLETED", its non-money half.</li>
 * </ul>
 *
 * <p><b>How this gets replaced:</b> {@code payment}'s port registers its own
 * {@code @Primary} implementation. {@code @ConditionalOnMissingBean} is NOT used
 * here on purpose — it is only evaluated for auto-configuration {@code @Bean}
 * methods and silently never fires for component-scanned {@code @Component}s
 * (documented on {@code dish}'s {@code ZeroDishStatsProvider}, where it cost a
 * debugging cycle).
 */
@Component
@Slf4j
public class NoPaymentOrderPaymentGateway implements OrderPaymentGateway {

    private static final String NOT_PORTED = "payment module is not ported yet";

    @Override
    public PaymentSession createCodPayment(UUID checkoutUid) {
        log.debug("COD payment record skipped for checkout {} ({})", checkoutUid, NOT_PORTED);
        return PaymentSession.none();
    }

    @Override
    public PaymentSession createPayosPayment(UUID checkoutUid, String bankCode) {
        log.debug("PayOS session skipped for checkout {} ({})", checkoutUid, NOT_PORTED);
        return PaymentSession.none();
    }

    @Override
    public void cancelPayment(UUID paymentUid, String reason) {
        // no session was ever created
    }

    @Override
    public void syncPaymentStatus(UUID checkoutUid) {
        // nothing to reconcile against
    }

    @Override
    public RefundResult handleOrderCancellationRefund(UUID orderUid, String reason) {
        return RefundResult.skipped(NOT_PORTED);
    }

    @Override
    public SettlementResult createSettlementRecord(UUID orderUid, CustomUser chef) {
        return SettlementResult.none();
    }

    @Override
    public Optional<PaymentStatus> settleCompletedOrder(Order order, BigDecimal chefPayout) {
        return Optional.empty();
    }

    @Override
    public Optional<PaymentStatus> currentPaymentStatus(UUID checkoutUid) {
        return Optional.empty();
    }

    @Override
    public Optional<String> refundStatus(UUID checkoutUid) {
        return Optional.empty();
    }
}
