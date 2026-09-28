package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.order.service.OrderPaymentGateway;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.entity.SettlementRecord;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionStateRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The real {@link OrderPaymentGateway} — {@code @Primary} over {@code order}'s
 * {@code NoPaymentOrderPaymentGateway} (same seam pattern as {@code dish}'s providers; no
 * {@code @ConditionalOnMissingBean}, which never fires for component-scanned beans).
 *
 * <p>Each method is the exact Django call {@code order/services/__init__.py} makes on its
 * {@code PaymentService}; see {@link OrderPaymentGateway}'s javadoc for the mapping.
 *
 * <h2>Transaction boundaries against {@code order}'s</h2>
 * <ul>
 *   <li>{@link #settleCompletedOrder} and {@link #createSettlementRecord} JOIN
 *       {@code completeOrderWithRelease}'s transaction — Django runs them inside
 *       {@code complete_order_with_release}'s {@code @transaction.atomic}, so the COMPLETED
 *       status, the settlement rows, the chef's RELEASE credit and the RELEASED state commit
 *       or roll back together.</li>
 *   <li>{@link #handleOrderCancellationRefund} runs in its own transaction
 *       ({@link PaymentRefundService} — see its javadoc for the savepoint discussion).</li>
 *   <li>{@link #syncPaymentStatus} SUSPENDS the caller's (read-only) transaction: Django runs
 *       the sync under autocommit, and it can write (HOLDING, orders, refunds).</li>
 * </ul>
 */
@Primary
@Component
@RequiredArgsConstructor
public class PaymentOrderPaymentGateway implements OrderPaymentGateway {

    private final PaymentService paymentService;
    private final PaymentRefundService refundService;
    private final SettlementService settlementService;
    private final PaymentStateService stateService;
    private final WalletService walletService;
    private final PaymentTransactionRepository paymentRepository;
    private final PaymentTransactionStateRepository stateRepository;
    private final PaymentTx tx;
    private final EscrowBook escrowBook;

    /** Django {@code create_cod_payment(checkout_uid)}. */
    @Override
    public PaymentSession createCodPayment(UUID checkoutUid) {
        PaymentTransaction payment = paymentService.createCodPayment(checkoutUid);
        PaymentTransactionState state = stateService.ensureState(payment);
        return new PaymentSession(payment.getUid(), state.getPaymentUrl(), state.getTransactionId(), null);
    }

    /**
     * Django {@code create_payment(checkout_uid, PAYOS, bank_code)} plus {@code place_order}'s
     * response fields: {@code payment_url}, {@code payment_uid}, {@code transaction_id},
     * {@code qr_code = gw.get("qrCode") or gw.get("qr_code")}.
     */
    @Override
    public PaymentSession createPayosPayment(UUID checkoutUid, String bankCode) {
        PaymentTransaction payment = paymentService.createPayment(checkoutUid, "PAYOS", bankCode, null);
        PaymentTransactionState state = stateService.ensureState(payment);
        Map<String, Object> gw = state.getGatewayResponse() != null ? state.getGatewayResponse() : Map.of();
        Object qr = PyCompat.truthy(gw.get("qrCode")) ? gw.get("qrCode") : gw.get("qr_code");
        return new PaymentSession(payment.getUid(), state.getPaymentUrl(), state.getTransactionId(),
                qr == null ? null : String.valueOf(qr));
    }

    /** Django {@code cancel_payment(payment_uid, reason)} (its result is ignored by place_order). */
    @Override
    public void cancelPayment(UUID paymentUid, String reason) {
        if (paymentUid != null) {
            paymentService.cancelPayment(paymentUid, reason);
        }
    }

    /**
     * Django: {@code if hasattr(order.checkout, 'payment_transaction'):
     * get_payment_status(payment.uid, sync_with_gateway=True)} — under autocommit, hence
     * {@link PaymentTx#notSupported}.
     */
    @Override
    public void syncPaymentStatus(UUID checkoutUid) {
        tx.notSupported(() -> {
            Optional<PaymentTransaction> payment = tx.required(() -> paymentRepository.findByCheckoutUid(checkoutUid));
            payment.ifPresent(p -> paymentService.getPaymentStatus(p.getUid(), true));
        });
    }

    @Override
    public RefundResult handleOrderCancellationRefund(UUID orderUid, String reason) {
        Map<String, Object> result = refundService.handleOrderCancellationRefund(orderUid, reason);
        boolean success = PyCompat.truthy(result.get("success"));
        Object error = result.get("error");
        return new RefundResult(success, error == null ? null : PyCompat.str(error));
    }

    @Override
    public SettlementResult createSettlementRecord(UUID orderUid, CustomUser chef) {
        SettlementRecord record = settlementService.createSettlementRecord(orderUid, chef);
        return new SettlementResult(record.getChefPayoutAmount());
    }

    /**
     * The tail of Django's {@code complete_order_with_release}:
     * <ul>
     *   <li>PayOS with the payment HOLDING or SUCCESS → {@code credit_internal_wallet(chef,
     *       chef_payout, RELEASE, reference "release_{order_uid}")} — the escrow release, the
     *       moment the chef's money becomes spendable — then state → RELEASED once no order of
     *       the checkout is left in escrow (per-order, see {@link #releaseEscrowForOrder}).
     *       PORT-NOTE (preserved): if the settlement step failed ({@code chef_payout == 0}) the
     *       credit raises "Amount must be greater than zero" and the whole completion rolls back
     *       (500), exactly as in Django.</li>
     *   <li>COD with a payment → state → SUCCESS (no escrow; no wallet movement).</li>
     * </ul>
     * Django does not copy the new status onto {@code order.payment_status} here; the returned
     * value is informational ({@code order} ignores it too).
     */
    @Override
    public Optional<PaymentStatus> settleCompletedOrder(Order order, BigDecimal chefPayout) {
        PaymentMethod method = order.getCheckout().getPaymentMethod();
        if (method == PaymentMethod.PAYOS) {
            return tx.required(() -> releaseEscrowForOrder(order, chefPayout));
        }
        Optional<PaymentTransaction> payment = tx.required(() -> paymentRepository.findByCheckoutUid(order.getCheckout().getUid()));
        if (method == PaymentMethod.COD && payment.isPresent()) {
            stateService.setPaymentState(payment.get(), PaymentStatus.SUCCESS, "cod_complete", null, "order_complete", null);
            return Optional.of(PaymentStatus.SUCCESS);
        }
        return Optional.empty();
    }

    /**
     * PayOS escrow RELEASE for ONE order — FIX for payment open question #3. Django gated the
     * credit on the checkout-wide state (HOLDING/SUCCESS) and then set it RELEASED, so in a
     * multi-chef checkout only the first completed order's chef was ever paid. Now, under
     * {@code SELECT ... FOR UPDATE} on the payment row (the same lock refunds take, so release
     * and refund decisions for one payment are serialized):
     * <ul>
     *   <li>skip if THIS order already has a RELEASE (exactly-once) or a REFUND ledger line;</li>
     *   <li>otherwise, with the payment HOLDING/SUCCESS, credit the chef this order's settlement
     *       payout (90%);</li>
     *   <li>state → RELEASED only when every other order of the checkout is out of escrow
     *       ({@link EscrowBook#siblings}); before that an {@code ORDER_RELEASED} event is recorded
     *       and the state stays HOLDING, so sibling orders remain releasable/refundable.</li>
     * </ul>
     * Single-order checkouts behave exactly as before (credit, then RELEASED).
     */
    private Optional<PaymentStatus> releaseEscrowForOrder(Order order, BigDecimal chefPayout) {
        Optional<PaymentTransaction> locked = paymentRepository.findForUpdateByCheckoutUid(order.getCheckout().getUid());
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        PaymentTransaction payment = locked.get();
        PaymentTransactionState state = stateService.ensureState(payment);
        if (state.getStatus() != PaymentStatus.HOLDING && state.getStatus() != PaymentStatus.SUCCESS) {
            return Optional.empty();
        }
        if (escrowBook.isReleased(order.getUid()) || escrowBook.isRefunded(order.getUid())) {
            return Optional.of(state.getStatus());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("payment_method", "PAYOS");
        metadata.put("stage", "ESCROW_RELEASE");
        walletService.credit(order.getChef().getId(), chefPayout, WalletTransactionType.RELEASE,
                order.getUid(), "release_" + order.getUid(), "Escrow release for order " + order.getUid(),
                metadata);
        EscrowBook.Siblings siblings = escrowBook.siblings(order.getCheckout().getUid(), order.getUid());
        if (siblings.allResolved()) {
            stateService.setPaymentState(payment, PaymentStatus.RELEASED, "escrow_release", null, "order_complete", null);
            return Optional.of(PaymentStatus.RELEASED);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "escrow_release");
        payload.put("order_uid", order.getUid().toString());
        payload.put("chef_id", order.getChef().getId());
        payload.put("amount", chefPayout.doubleValue());
        stateService.recordEvent(payment, "ORDER_RELEASED", state.getStatus().name(), state.getStatus().name(),
                payload, null, "order_complete");
        return Optional.of(state.getStatus());
    }

    /** {@code order.checkout.payment_transaction.state.status}, straight from the database. */
    @Override
    public Optional<PaymentStatus> currentPaymentStatus(UUID checkoutUid) {
        return tx.required(() -> stateRepository.findStatusByCheckoutUid(checkoutUid));
    }

    /**
     * Django {@code OrderMapper.to_response_with_info}: only when the payment state is
     * CANCELLED → {@code gateway_response.get('refund_status', 'PROCESSING')} (or 'PROCESSING'
     * without a gateway response); otherwise none.
     */
    @Override
    public Optional<String> refundStatus(UUID checkoutUid) {
        return tx.required(() -> paymentRepository.findByCheckoutUid(checkoutUid)
                .flatMap(p -> stateRepository.findByPaymentTransactionId(p.getId()))
                .filter(s -> s.getStatus() == PaymentStatus.CANCELLED)
                .map(s -> {
                    Map<String, Object> gw = s.getGatewayResponse();
                    if (gw == null || gw.isEmpty()) {
                        return "PROCESSING";
                    }
                    if (!gw.containsKey("refund_status")) {
                        return "PROCESSING";
                    }
                    Object value = gw.get("refund_status");
                    return value == null ? null : PyCompat.str(value);
                }));
    }
}
