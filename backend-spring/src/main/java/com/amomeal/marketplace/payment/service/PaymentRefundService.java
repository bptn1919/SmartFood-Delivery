package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.LedgerType;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.entity.PayoutLedger;
import com.amomeal.marketplace.payment.entity.SettlementRecord;
import com.amomeal.marketplace.payment.entity.SettlementStatus;
import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import com.amomeal.marketplace.payment.provider.PayOsProvider;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.PaymentOrderRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import com.amomeal.marketplace.payment.repository.PayoutLedgerRepository;
import com.amomeal.marketplace.payment.repository.SettlementRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port of ../../backend/payment/services.py::PaymentService.handle_order_cancellation_refund —
 * the money half of cancelling an order.
 *
 * <h2>What it does (Django, verbatim)</h2>
 * <b>PayOS</b>, inside ONE atomic block that first takes {@code SELECT ... FOR UPDATE} on the
 * checkout's {@code payment_transactions} row ("Acquire row-level lock before status check to
 * prevent double-refund race"):
 * <ul>
 *   <li>this ORDER already has a REFUND ledger line, or the state is REFUNDED →
 *       {@code {"success": True, "skipped": True}} — the idempotency guard, evaluated UNDER the
 *       lock, so N concurrent cancellations credit the customer once. (Per-order check = the fix
 *       for open question #3; Django only had the checkout-wide REFUNDED check.)</li>
 *   <li>HOLDING/SUCCESS (money received) → {@code assert_payment_confirmed} (state + signed
 *       webhook/reconciliation evidence + intact event chain), then credit the ORDER's
 *       {@code total_price} to the customer's INTERNAL WALLET ({@code REFUND}; PayOS cannot
 *       refund a PAID link), then — only once every order of the checkout is out of escrow —
 *       state → REFUNDED (or RELEASED if a sibling order was paid to its chef); before that
 *       an {@code ORDER_REFUNDED} event is recorded and the state stays HOLDING
 *       (see {@link EscrowBook});</li>
 *   <li>PENDING (not paid) → cancel the PayOS link, state → CANCELLED;</li>
 *   <li>anything else → failure dict.</li>
 * </ul>
 * <b>COD</b>: mark an existing settlement CANCELLED + a PAYOUT_REVERSAL ledger line, state →
 * CANCELLED. Every exception becomes {@code {"success": False, "error": str(e)}}.
 *
 * <h2>Transaction boundary — deliberate, flagged</h2>
 * Django's PayOS block is a nested {@code atomic} = a SAVEPOINT inside {@code cancel_order}'s
 * transaction. JPA has no safe savepoint equivalent (a rolled-back savepoint leaves dirty
 * entities, e.g. a credited wallet, in the persistence context to be flushed later), so the
 * whole body runs in {@code REQUIRES_NEW}: all-or-nothing on its own and committed
 * independently. Difference: if {@code cancel_order} fails AFTER the refund committed, the
 * refund stays (Django would roll it back). A retried cancel then hits the REFUNDED guard, so
 * this can never double-credit. It also lets the late-webhook path (no outer transaction)
 * and {@code order}'s cancel (outer transaction) share one code path. See PROGRESS.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRefundService {

    private final PaymentOrderRepository orderRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final SettlementRecordRepository settlementRepository;
    private final PayoutLedgerRepository ledgerRepository;
    private final PaymentStateService stateService;
    private final WalletService walletService;
    private final PayOsProvider payOsProvider;
    private final PaymentTx tx;
    private final EscrowBook escrowBook;

    /** Django {@code handle_order_cancellation_refund(order_uid, reason)} — never throws. */
    public Map<String, Object> handleOrderCancellationRefund(UUID orderUid, String reason) {
        try {
            return tx.requiresNew(() -> refundInTransaction(orderUid, reason));
        } catch (RuntimeException ex) {
            log.error("[Refund] Error processing refund: {}", ex.toString());
            return result("success", false, "error", String.valueOf(ex.getMessage()));
        }
    }

    private Map<String, Object> refundInTransaction(UUID orderUid, String reason) {
        Order order = orderRepository.findWithCheckout(orderUid)
                .orElseThrow(() -> new IllegalStateException("Order matching query does not exist."));
        PaymentMethod method = order.getCheckout().getPaymentMethod();

        if (method == PaymentMethod.PAYOS) {
            Optional<PaymentTransaction> locked = paymentRepository.findForUpdateByCheckoutUid(order.getCheckout().getUid());
            if (locked.isEmpty()) {
                return result("success", false, "error", "Payment transaction not found");
            }
            PaymentTransaction payment = locked.get();
            PaymentTransactionState state = stateService.ensureState(payment);

            // Idempotency guard — checked under the row lock. FIX (open question #3): PER ORDER.
            // Django only looked at the checkout-wide state, so in a multi-chef checkout the first
            // cancelled order's refund marked the whole payment REFUNDED and every later order of
            // the same checkout was skipped as "already refunded" (never refunded at all).
            if (escrowBook.isRefunded(order.getUid())) {
                return result("success", true, "skipped", true, "refund_status", "ALREADY_REFUNDED");
            }
            // Django's own guard, kept: REFUNDED now means every order of the checkout is resolved.
            if (state.getStatus() == PaymentStatus.REFUNDED) {
                return result("success", true, "skipped", true, "refund_status", "ALREADY_REFUNDED");
            }

            // Case 1: money is at the merchant (HOLDING/SUCCESS) -> credit the customer's internal wallet.
            if (state.getStatus() == PaymentStatus.HOLDING || state.getStatus() == PaymentStatus.SUCCESS) {
                // Defensive (not in Django): this order's share already went to its chef.
                if (escrowBook.isReleased(order.getUid())) {
                    return result("success", false,
                            "error", "Order " + order.getUid() + " was already released to the chef; cannot refund");
                }
                stateService.assertPaymentConfirmed(payment, false);
                BigDecimal refundAmount = order.getTotalPrice() != null ? order.getTotalPrice() : BigDecimal.ZERO;
                WalletTransaction credit = null;
                if (order.getOwner() != null) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("payment_method", "PAYOS");
                    metadata.put("cancellation_reason", reason);
                    credit = walletService.credit(order.getOwner().getId(), refundAmount, WalletTransactionType.REFUND,
                            order.getUid(), "refund_" + order.getUid(),
                            "Refund for cancelled order " + order.getUid(), metadata);
                }
                Map<String, Object> refundInfo = new LinkedHashMap<>();
                refundInfo.put("type", "INTERNAL_WALLET_CREDIT");
                refundInfo.put("amount", refundAmount.doubleValue());
                refundInfo.put("reason", reason);
                refundInfo.put("order_uid", order.getUid().toString());
                Map<String, Object> gatewayResponse = copyOf(state.getGatewayResponse());
                gatewayResponse.put("refund_info", refundInfo);
                gatewayResponse.put("refund_reason", reason);
                gatewayResponse.put("refunded_at", PyCompat.isoformat(Instant.now()));
                gatewayResponse.put("wallet_credit_tx", credit != null ? credit.getUid().toString() : null);

                // FIX (open question #3): the checkout-level state moves only when the LAST order
                // leaves escrow — REFUNDED if nothing was released, RELEASED if a sibling's chef
                // was paid. Until then it stays HOLDING (siblings stay refundable/releasable) and
                // this order's refund is recorded as an ORDER_REFUNDED audit event.
                EscrowBook.Siblings siblings = escrowBook.siblings(order.getCheckout().getUid(), order.getUid());
                if (siblings.allResolved()) {
                    stateService.transition(payment, siblings.anyReleased() ? PaymentStatus.RELEASED : PaymentStatus.REFUNDED,
                            reason, gatewayResponse, "order_cancel_refund", null, null);
                } else {
                    Map<String, Object> eventPayload = new LinkedHashMap<>();
                    eventPayload.put("reason", reason);
                    eventPayload.put("gateway_payload", gatewayResponse);
                    stateService.recordEvent(payment, "ORDER_REFUNDED", state.getStatus().name(), state.getStatus().name(),
                            eventPayload, null, "order_cancel_refund");
                }

                log.info("[Refund] Credited {} VND to customer wallet for order {}", refundAmount, orderUid);
                return result("success", true, "payment_method", "PAYOS", "refund_status", "REFUNDED",
                        "message", "Refund processed to customer internal wallet",
                        "amount", refundAmount.doubleValue(),
                        "wallet_credit_tx", credit != null ? credit.getUid().toString() : null);
            }

            // Case 2: link not paid yet (PENDING) -> cancel the PayOS link.
            if (state.getStatus() == PaymentStatus.PENDING) {
                if (payment.getPayosOrderCode() != null) {
                    Map<String, Object> cancel = payOsProvider.cancelPayment(payment.getPayosOrderCode(),
                            reason != null && !reason.isEmpty() ? reason : "Order cancelled");
                    if (PyCompat.truthy(cancel.get("success"))) {
                        Map<String, Object> gatewayResponse = copyOf(state.getGatewayResponse());
                        gatewayResponse.put("cancel_info", cancel);
                        gatewayResponse.put("cancel_reason", reason);
                        gatewayResponse.put("cancelled_at", PyCompat.isoformat(Instant.now()));
                        stateService.transition(payment, PaymentStatus.CANCELLED, reason, gatewayResponse,
                                "order_cancel_refund", null, null);
                        return result("success", true, "payment_method", "PAYOS", "refund_status", "CANCELLED",
                                "message", "Payment link cancelled successfully");
                    }
                    return result("success", false, "payment_method", "PAYOS", "error", cancel.get("error"));
                }
                stateService.transition(payment, PaymentStatus.CANCELLED, reason, null, "order_cancel_refund", null, null);
                return result("success", true, "payment_method", "PAYOS", "refund_status", "CANCELLED");
            }

            return result("success", false,
                    "error", "Payment status is " + state.getStatus() + ", cannot process refund/cancellation");
        }

        if (method == PaymentMethod.COD) {
            Optional<SettlementRecord> settlement = settlementRepository.findFirstByOrderUid(order.getUid());
            if (settlement.isPresent()) {
                SettlementRecord record = settlement.get();
                record.setStatus(SettlementStatus.CANCELLED);
                record.setErrorReason(reason != null && !reason.isEmpty() ? reason : "Order cancelled");
                settlementRepository.saveAndFlush(record);
                // PORT-NOTE (preserved Django bug, unreachable in practice): Django creates this
                // ledger row WITHOUT settlement_record, a NOT NULL FK -> IntegrityError -> the
                // whole refund reports failure. Only reachable if a COD order is cancelled after
                // completion, which the order state machine forbids.
                ledgerRepository.saveAndFlush(PayoutLedger.builder()
                        .ledgerType(LedgerType.PAYOUT_REVERSAL)
                        .amount(record.getChefPayoutAmount())
                        .description("COD order cancelled: " + PyCompat.str(reason))
                        .orderUid(orderUid.toString())
                        .chefId(order.getChef() != null ? order.getChef().getId() : null)
                        .build());
            }
            paymentRepository.findByCheckoutUid(order.getCheckout().getUid()).ifPresent(payment -> {
                PaymentTransactionState state = stateService.ensureState(payment);
                if (state.getStatus() != PaymentStatus.CANCELLED && state.getStatus() != PaymentStatus.REFUNDED) {
                    stateService.transition(payment, PaymentStatus.CANCELLED,
                            reason != null && !reason.isEmpty() ? reason : "COD order cancelled",
                            null, "order_cancel_cod", null, null);
                }
            });
            return result("success", true, "payment_method", "COD", "refund_status", "CANCELLED",
                    "message", "COD order cancelled (no payment made yet)");
        }

        return result("success", false, "error", "Unknown payment method: " + method);
    }

    static Map<String, Object> copyOf(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    static Map<String, Object> result(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }
}
