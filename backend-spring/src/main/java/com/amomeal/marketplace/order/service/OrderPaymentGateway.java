package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.users.entity.CustomUser;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything {@code order} needs from {@code payment}, as one explicit seam —
 * the same pattern {@code dish} used for {@link
 * com.amomeal.marketplace.dish.service.ExpiredOrderHandler} and
 * {@code DishStatsProvider}.
 *
 * <p><b>Why:</b> Django's {@code OrderService} holds a live
 * {@code PaymentService} and calls eight of its methods
 * ({@code create_cod_payment}, {@code create_payment}, {@code cancel_payment},
 * {@code get_payment_status}, {@code handle_order_cancellation_refund},
 * {@code create_settlement_record}, {@code credit_internal_wallet},
 * {@code set_payment_state}). CLAUDE.md §7 ports {@code payment} AFTER
 * {@code order}, so that module does not exist yet — and half of it is a PayOS
 * HTTP integration plus a wallet/escrow ledger, far outside this task's scope.
 *
 * <p>{@link NoPaymentOrderPaymentGateway} is the default: it keeps the COD and
 * PayOS branches of {@code place_order} structurally intact (orders still go
 * DRAFT -&gt; PENDING, COD still confirms stock immediately, PayOS still leaves
 * holds RESERVED for the TTL sweep) while performing no money movement at all.
 * That is precisely the inventory-correctness half this port must get right; the
 * money half arrives with {@code payment}, which registers a {@code @Primary}
 * bean implementing this interface. Nothing in {@code order} changes then.
 */
public interface OrderPaymentGateway {

    /** Django: {@code PaymentService.create_cod_payment(checkout_uid)} — a PENDING COD record. */
    PaymentSession createCodPayment(UUID checkoutUid);

    /**
     * Django: {@code PaymentService.create_payment(checkout_uid, PAYOS, bank_code)} —
     * creates the PayOS payment session whose url/QR the FE opens.
     */
    PaymentSession createPayosPayment(UUID checkoutUid, String bankCode);

    /** Django: {@code PaymentService.cancel_payment(payment_uid, reason)}. */
    void cancelPayment(UUID paymentUid, String reason);

    /**
     * Django: {@code PaymentService.get_payment_status(payment_uid, sync_with_gateway=True)},
     * called opportunistically whenever a DRAFT order is read so a missed webhook
     * still gets reconciled (see ../../backend/ORDER_FLOW_ONBOARDING.md §Level 8,
     * "there is no background poller — the pull only happens when someone opens
     * the order"). Every Django call site wraps it in try/except and only logs.
     */
    void syncPaymentStatus(UUID checkoutUid);

    /**
     * Django: {@code PaymentService.handle_order_cancellation_refund(order_uid, reason)}.
     * {@code cancel_order} only inspects {@code result["success"]} and logs a warning
     * when false — it never aborts the cancellation.
     */
    RefundResult handleOrderCancellationRefund(UUID orderUid, String reason);

    /** Django: {@code PaymentService.create_settlement_record(order_uid, chef)}. */
    SettlementResult createSettlementRecord(UUID orderUid, CustomUser chef);

    /**
     * Django: the {@code credit_internal_wallet(RELEASE)} + {@code set_payment_state(RELEASED)}
     * pair (PayOS) or the bare {@code set_payment_state(SUCCESS)} (COD) at the end of
     * {@code complete_order_with_release}. Collapsed into one call because {@code order}
     * never needs the two apart.
     *
     * @return the payment status the order should now record, if the gateway moved it
     */
    Optional<PaymentStatus> settleCompletedOrder(Order order, BigDecimal chefPayout);

    /** Django: {@code order.checkout.payment_transaction.state.status}, read back after a refund. */
    Optional<PaymentStatus> currentPaymentStatus(UUID checkoutUid);

    /**
     * Django: the {@code refund_status} field of {@code OrderMapper.to_response_with_info} —
     * only non-null when the payment state is CANCELLED, defaulting to "PROCESSING".
     */
    Optional<String> refundStatus(UUID checkoutUid);

    /** Django's {@code PaymentTransaction} + its {@code state}, reduced to what {@code order} reads. */
    record PaymentSession(UUID paymentUid, String paymentUrl, String transactionId, String qrCode) {
        public static PaymentSession none() {
            return new PaymentSession(null, null, null, null);
        }
    }

    /** Django's {@code {"success": bool, "error": str}} dict. */
    record RefundResult(boolean success, String error) {
        public static RefundResult skipped(String why) {
            return new RefundResult(false, why);
        }
    }

    /** Django's {@code Settlement} row, reduced to the one field {@code order} reads. */
    record SettlementResult(BigDecimal chefPayoutAmount) {
        public static SettlementResult none() {
            return new SettlementResult(BigDecimal.ZERO);
        }
    }
}
