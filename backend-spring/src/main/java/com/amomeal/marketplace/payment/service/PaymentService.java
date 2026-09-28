package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.dish.entity.StockReservation;
import com.amomeal.marketplace.dish.entity.StockReservationStatus;
import com.amomeal.marketplace.dish.exception.StockReservationException;
import com.amomeal.marketplace.dish.repository.StockReservationRepository;
import com.amomeal.marketplace.dish.service.StockReservationService;
import com.amomeal.marketplace.order.entity.Checkout;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.order.repository.CheckoutRepository;
import com.amomeal.marketplace.order.service.OutboxService;
import com.amomeal.marketplace.payment.config.PaymentProperties;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.exception.PaymentHttpException;
import org.springframework.http.HttpStatus;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.provider.PayOsProvider;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.PaymentAppliedVoucherRepository;
import com.amomeal.marketplace.payment.repository.PaymentOrderRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port of the gateway/lifecycle half of ../../backend/payment/services.py::PaymentService:
 * payment creation (PayOS + COD), status/sync against PayOS, cancellation, invoices and the
 * PayOS webhook with its escrow-confirmation step ({@code _sync_successful_payment}).
 *
 * <h2>Transactions — mirrors Django's AUTOCOMMIT on these paths</h2>
 * Django runs {@code create_payment}, {@code sync_payment_by_order_code},
 * {@code handle_payos_webhook} and {@code _sync_successful_payment} without an enclosing
 * transaction: each ORM statement commits on its own, and the method deliberately keeps going
 * when a later step fails ("the sale is already legally final once payment succeeded"). The
 * methods here are therefore NOT transactional; every step runs in its own short transaction
 * via {@link PaymentTx#required}. This is load-bearing, not style: the late-webhook refund
 * ({@link PaymentRefundService}, its own transaction) must SEE the committed HOLDING state —
 * one enclosing transaction would make it read PENDING and then deadlock on the state row.
 * Callers that might be inside a transaction ({@code order}'s read paths) go through
 * {@link PaymentTx#notSupported} in {@link PaymentOrderPaymentGateway}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    public static final BigDecimal PLATFORM_FEE_PERCENT = new BigDecimal("0.10");

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentStateService stateService;
    private final PaymentRefundService refundService;
    private final PayOsProvider payOsProvider;
    private final CheckoutRepository checkoutRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentAppliedVoucherRepository appliedVoucherRepository;
    private final StockReservationService stockReservationService;
    private final StockReservationRepository stockReservationRepository;
    private final OutboxService outboxService;
    private final PaymentProperties properties;
    private final PaymentTx tx;

    // =====================================================================
    // Ownership guards (post-port decision 2026-09-25: Django had none)
    // =====================================================================

    private static PaymentHttpException forbidden() {
        return new PaymentHttpException(HttpStatus.FORBIDDEN, "You don't have permission to access this payment");
    }

    /**
     * The caller must own the checkout (the customer who placed it); ADMIN also passes when
     * {@code allowAdmin}. An unknown checkout is left to the caller's own not-found handling.
     */
    public void assertCheckoutAccess(UUID checkoutUid, CustomUser user, boolean allowAdmin) {
        if (allowAdmin && user.isAdmin()) {
            return;
        }
        Optional<Long> ownerId = tx.required(() -> checkoutRepository.findById(checkoutUid)
                .map(c -> c.getOwner() == null ? -1L : c.getOwner().getId()));
        if (ownerId.isPresent() && !ownerId.get().equals(user.getId())) {
            throw forbidden();
        }
    }

    /** Same rule keyed by payment uid (the payment's checkout owner). Unknown uid: left to the caller. */
    public void assertPaymentAccess(UUID paymentUid, CustomUser user, boolean allowAdmin) {
        Optional<PaymentTransaction> payment = tx.required(() -> paymentRepository.findByUid(paymentUid));
        payment.ifPresent(p -> assertCheckoutAccess(p.getCheckoutUid(), user, allowAdmin));
    }

    /** Same rule keyed by PayOS order code. Unknown code: left to the caller (raw PayOS lookup). */
    public void assertOrderCodeAccess(long orderCode, CustomUser user, boolean allowAdmin) {
        Optional<PaymentTransaction> payment = tx.required(() -> paymentRepository.findByPayosOrderCode(orderCode));
        payment.ifPresent(p -> assertCheckoutAccess(p.getCheckoutUid(), user, allowAdmin));
    }

    // =====================================================================
    // create_payment / create_cod_payment
    // =====================================================================

    /**
     * Django {@code create_payment(checkout_uid, payment_method, bank_code, language)}.
     * <ul>
     *   <li>An existing payment for the checkout (backend-edit commit 3fa7cdc — PaymentTransaction
     *       is append-only, it is never deleted any more): SUCCESS → {@code ValueError("Payment
     *       already completed")}; FAILED → {@code ValueError("This payment transaction has failed.
     *       Please create a new checkout.")}; PENDING → the existing payment is REUSED and returned
     *       as-is (same PayOS link), unless it was created for another payment method →
     *       {@code ValueError("A payment transaction already exists for another payment method.")}.
     *       PORT-NOTE (preserved): any other existing state (e.g. HOLDING) falls through and the
     *       second insert violates the one-payment-per-checkout constraint.</li>
     *   <li>PAYOS: call PayOS FIRST, then insert the payment + PENDING state (gateway_response =
     *       provider result) + a {@code PAYOS_CREATE} event; a PayOS failure transitions it to
     *       FAILED and raises. Anything else that throws with no payment row yet inserts a
     *       FAILED one, then re-raises.</li>
     *   <li>COD: payment + SUCCESS state, {@code transaction_id = "COD-{checkout_uid}"}.</li>
     * </ul>
     * Amount is always {@code checkout.total_price} — never taken from the client.
     */
    public PaymentTransaction createPayment(UUID checkoutUid, String paymentMethod, String bankCode, String language) {
        PaymentMethod method = "PAYOS".equals(paymentMethod) ? PaymentMethod.PAYOS
                : "COD".equals(paymentMethod) ? PaymentMethod.COD : null;
        Checkout checkout = tx.required(() -> checkoutRepository.findById(checkoutUid))
                .orElseThrow(() -> new PaymentValueError("Checkout " + checkoutUid + " not found"));

        Optional<PaymentTransaction> existing = tx.required(() -> paymentRepository.findByCheckoutUid(checkoutUid));
        // Python quirk kept: `"payment" not in locals()` is False whenever this branch bound `payment`.
        boolean paymentBound = existing.isPresent();
        if (existing.isPresent()) {
            PaymentTransaction old = existing.get();
            PaymentTransactionState state = stateService.ensureState(old);
            if (state.getStatus() == PaymentStatus.SUCCESS) {
                throw new PaymentValueError("Payment already completed");
            }
            if (state.getStatus() == PaymentStatus.FAILED) {
                throw new PaymentValueError("This payment transaction has failed. Please create a new checkout.");
            }
            if (state.getStatus() == PaymentStatus.PENDING) {
                // PaymentTransaction is append-only. Reuse the existing payment record and its
                // current state instead of deleting the parent row.
                if (old.getPaymentMethod() == null || !old.getPaymentMethod().name().equals(paymentMethod)) {
                    throw new PaymentValueError("A payment transaction already exists for another payment method.");
                }
                return old;
            }
        }

        if (method == PaymentMethod.PAYOS) {
            UUID paymentUid = UUID.randomUUID();
            long orderCode = generateOrderCode(paymentUid);
            PaymentTransaction payment = null;
            try {
                Map<String, Object> paymentData = payOsProvider.createPayment(orderCode,
                        checkout.getTotalPrice().longValue(), "DH " + orderCode);
                log.info("[PayOS] Payment data: {}", paymentData);

                payment = insertPayment(paymentUid, checkoutUid, method, checkout.getTotalPrice(), orderCode);
                PaymentTransactionState state = stateService.ensureState(payment, PaymentStatus.PENDING, null, paymentData);
                if (PyCompat.truthy(paymentData.get("success"))) {
                    Object linkId = paymentData.get("payment_link_id");
                    String checkoutUrl = paymentData.get("checkout_url") == null ? "" : String.valueOf(paymentData.get("checkout_url"));
                    String paymentLinkId = PyCompat.truthy(linkId) ? String.valueOf(linkId) : null;
                    // Fallback: extract from URL if provider didn't return payment_link_id directly
                    if (paymentLinkId == null && checkoutUrl.contains("/web/")) {
                        paymentLinkId = checkoutUrl.substring(checkoutUrl.lastIndexOf("/web/") + 5);
                    }
                    state.setPaymentUrl(checkoutUrl);
                    state.setPayosPaymentLinkId(paymentLinkId);
                    state.setTransactionId(paymentLinkId);
                    state = stateService.save(state);
                }
                stateService.recordEvent(payment, "PAYOS_CREATE", state.getStatus().name(), state.getStatus().name(),
                        paymentData, null, "payos_create");

                if (!PyCompat.truthy(paymentData.get("success"))) {
                    stateService.transition(payment, PaymentStatus.FAILED, PyCompat.str(paymentData.get("error")),
                            paymentData, "payos_create", null, null);
                    throw new PaymentValueError("Failed to create PayOS payment: " + PyCompat.str(paymentData.get("error")));
                }
                return payment;
            } catch (RuntimeException ex) {
                log.warn("[PayOS] Exception: {}", ex.getMessage());
                if (payment == null && !paymentBound) {
                    PaymentTransaction failed = insertPayment(paymentUid, checkoutUid, method, checkout.getTotalPrice(), orderCode);
                    stateService.ensureState(failed, PaymentStatus.FAILED, null, null);
                }
                throw ex;
            }
        } else if (method == PaymentMethod.COD) {
            PaymentTransaction payment = insertPayment(UUID.randomUUID(), checkoutUid, method, checkout.getTotalPrice(), null);
            PaymentTransactionState codState = stateService.ensureState(payment, PaymentStatus.SUCCESS, Instant.now(), null);
            codState.setTransactionId("COD-" + checkoutUid);
            stateService.save(codState);
            return payment;
        }
        // Django: neither branch -> `return payment`: the payment found above if one was bound
        // (even a just-deleted one), else UnboundLocalError.
        if (existing.isPresent()) {
            return existing.get();
        }
        throw new IllegalStateException("cannot access local variable 'payment' where it is not associated with a value");
    }

    private PaymentTransaction insertPayment(UUID uid, UUID checkoutUid, PaymentMethod method, BigDecimal amount,
                                             Long orderCode) {
        return tx.required(() -> paymentRepository.saveAndFlush(PaymentTransaction.builder()
                .uid(uid)
                .checkoutUid(checkoutUid)
                .paymentMethod(method)
                .amount(amount)
                .payosOrderCode(orderCode)
                .build()));
    }

    /**
     * Django {@code create_cod_payment(checkout_uid)} — what {@code place_order} uses for COD:
     * returns the existing COD payment if any (a non-COD one → {@code ValueError("A non-COD
     * payment transaction already exists for this checkout.")}, backend-edit commit 3fa7cdc),
     * else a PENDING one (it becomes SUCCESS when the order is COMPLETED).
     */
    public PaymentTransaction createCodPayment(UUID checkoutUid) {
        Checkout checkout = tx.required(() -> checkoutRepository.findById(checkoutUid))
                .orElseThrow(() -> new PaymentValueError("Checkout " + checkoutUid + " not found"));
        Optional<PaymentTransaction> existing = tx.required(() -> paymentRepository.findByCheckoutUid(checkoutUid));
        if (existing.isPresent()) {
            if (existing.get().getPaymentMethod() != PaymentMethod.COD) {
                throw new PaymentValueError("A non-COD payment transaction already exists for this checkout.");
            }
            return existing.get();
        }
        PaymentTransaction payment = insertPayment(UUID.randomUUID(), checkoutUid, PaymentMethod.COD,
                checkout.getTotalPrice(), null);
        PaymentTransactionState codState = stateService.ensureState(payment, PaymentStatus.PENDING, null, null);
        codState.setTransactionId("COD-" + checkoutUid);
        stateService.save(codState);
        log.info("[COD Payment] Created payment transaction {} for checkout {}", payment.getUid(), checkoutUid);
        return payment;
    }

    /**
     * Django {@code _generate_order_code}: {@code int(md5(str(uid)).hexdigest(), 16) % 10**13}.
     * PORT-NOTE: 13 decimal digits of an MD5 — collisions between two payments are possible in
     * principle (birthday bound ~10^6.5 payments); Django has no uniqueness constraint on
     * {@code payos_order_code} either, and a collision makes the webhook lookup ambiguous.
     */
    public static long generateOrderCode(UUID paymentUid) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(paymentUid.toString().getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).mod(BigInteger.TEN.pow(13)).longValueExact();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // =====================================================================
    // status / info / sync
    // =====================================================================

    /**
     * Django {@code get_payment_status(payment_uid, sync_with_gateway=True)}: a PENDING PayOS
     * payment is first reconciled against PayOS ({@link #syncPaymentByOrderCode}).
     */
    public PaymentTransaction getPaymentStatus(UUID paymentUid, boolean syncWithGateway) {
        PaymentTransaction payment = tx.required(() -> paymentRepository.findByUid(paymentUid))
                .orElseThrow(() -> new PaymentValueError("Payment " + paymentUid + " not found"));
        PaymentTransactionState state = stateService.ensureState(payment);
        if (syncWithGateway && payment.getPaymentMethod() == PaymentMethod.PAYOS
                && state.getStatus() == PaymentStatus.PENDING && payment.getPayosOrderCode() != null) {
            Map<String, Object> sync = syncPaymentByOrderCode(payment.getPayosOrderCode());
            log.info("[PayOS Sync] Payment {} sync result status={}", paymentUid, sync.get("status"));
        }
        return payment;
    }

    /** Django {@code get_payment_info_by_order_code(order_code)} — raw provider passthrough. */
    public Map<String, Object> getPaymentInfoByOrderCode(long orderCode) {
        return payOsProvider.getPaymentInfo(orderCode);
    }

    /**
     * Django {@code sync_payment_by_order_code(order_code)} — the PULL fallback (return URL,
     * status polling) for a missed/late webhook. Asks PayOS directly; PAID → a
     * {@code RECONCILIATION_CONFIRMED} event (evidence that later authorizes a refund) and the
     * same confirmation as the webhook; CANCELLED/EXPIRED → CANCELLED/FAILED + orders cancelled.
     *
     * <p>Post-port fix (2026-09-25): idempotent for an already HOLDING/SUCCESS/RELEASED/REFUND_*
     * payment (Django re-applied {@code _sync_successful_payment}, resetting orders a chef had
     * already advanced back to CONFIRMED_SYSTEM). The public
     * {@code /api/payment/payos/return?code=00&orderCode=...} endpoint triggers this with no
     * authentication, but it only ever acts on PayOS's own answer.
     */
    public Map<String, Object> syncPaymentByOrderCode(long orderCode) {
        Optional<PaymentTransaction> found = tx.required(() -> paymentRepository.findByPayosOrderCode(orderCode));
        if (found.isEmpty()) {
            return PaymentRefundService.result("success", false, "error", "Payment with order code " + orderCode + " not found");
        }
        PaymentTransaction payment = found.get();
        if (payment.getPaymentMethod() != PaymentMethod.PAYOS) {
            return PaymentRefundService.result("success", false, "error", "Only PayOS payments can be synced by order code");
        }
        Map<String, Object> payosInfo = payOsProvider.getPaymentInfo(orderCode);
        if (!PyCompat.truthy(payosInfo.get("success"))) {
            return payosInfo;
        }
        Object payosStatus = payosInfo.get("status");
        if ("PAID".equals(payosStatus)) {
            PaymentTransactionState pre = stateService.ensureState(payment);
            // Post-port fix (2026-09-25): idempotent - an already-confirmed payment must not be
            // re-applied (its bulk update would reset orders a chef already advanced).
            if (pre.getStatus() == PaymentStatus.HOLDING || pre.getStatus() == PaymentStatus.SUCCESS
                    || pre.getStatus() == PaymentStatus.RELEASED || pre.getStatus() == PaymentStatus.REFUND_PENDING
                    || pre.getStatus() == PaymentStatus.REFUNDED) {
                return PaymentRefundService.result("success", true, "status", pre.getStatus().name(),
                        "payment_uid", payment.getUid().toString(), "order_code", orderCode,
                        "message", "Already processed");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("gateway_status", payosStatus);
            payload.put("order_code", orderCode);
            stateService.recordEvent(payment, "RECONCILIATION_CONFIRMED", pre.getStatus().name(),
                    PaymentStatus.HOLDING.name(), payload, null, "payos_return");
            SyncResult sync = syncSuccessfulPayment(payment, payosInfo, "payos_return");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("status", PaymentStatus.HOLDING.name());
            out.put("payment_uid", payment.getUid().toString());
            out.put("order_code", orderCode);
            out.put("orders_count", sync.ordersCount());
            out.put("vouchers_used", sync.vouchersUsed());
            out.put("split_summary", sync.splitSummary());
            return out;
        }
        if ("CANCELLED".equals(payosStatus) || "EXPIRED".equals(payosStatus)) {
            PaymentStatus newStatus = "CANCELLED".equals(payosStatus) ? PaymentStatus.CANCELLED : PaymentStatus.FAILED;
            String lower = ((String) payosStatus).toLowerCase();
            stateService.transition(payment, newStatus, "payos_" + lower, payosInfo, "payos_return", null, null);
            cancelOrdersForPayment(payment, "PayOS payment " + lower);
            return PaymentRefundService.result("success", true, "status", newStatus.name(),
                    "payment_uid", payment.getUid().toString(), "order_code", orderCode);
        }
        PaymentTransactionState state = stateService.ensureState(payment);
        return PaymentRefundService.result("success", true, "status", state.getStatus().name(),
                "payment_uid", payment.getUid().toString(), "order_code", orderCode, "gateway_status", payosStatus);
    }

    // =====================================================================
    // cancel_payment / cancel_payment_with_refund / invoices
    // =====================================================================

    /**
     * Django {@code cancel_payment(payment_uid, reason)} (POST /api/payment/{uid}/cancel, and
     * {@code place_order}'s failure path).
     *
     * <p>PORT-NOTE (preserved Django bug, flagged): the guard is inverted —
     * {@code if state.status not in [SUCCESS, HOLDING, REFUND_PENDING]: return "Cannot cancel
     * successful payment"} — so a PENDING (unpaid) payment can NEVER be cancelled here, while a
     * paid one can: HOLDING → REFUND_PENDING (no money moves), SUCCESS/REFUND_PENDING → PayOS
     * cancel call → CANCELLED. The {@code status == CANCELLED} check after it is unreachable.
     */
    public Map<String, Object> cancelPayment(UUID paymentUid, String reason) {
        Optional<PaymentTransaction> found = tx.required(() -> paymentRepository.findByUid(paymentUid));
        if (found.isEmpty()) {
            return PaymentRefundService.result("success", false, "error", "Payment " + paymentUid + " not found");
        }
        PaymentTransaction payment = found.get();
        PaymentTransactionState state = stateService.ensureState(payment);
        PaymentStatus status = state.getStatus();
        if (status != PaymentStatus.SUCCESS && status != PaymentStatus.HOLDING && status != PaymentStatus.REFUND_PENDING) {
            return PaymentRefundService.result("success", false, "error", "Cannot cancel successful payment");
        }
        if (status == PaymentStatus.HOLDING) {
            stateService.transition(payment, PaymentStatus.REFUND_PENDING, "cancel_requested", null, "cancel_payment", null, null);
            return PaymentRefundService.result("success", true, "status", "REFUND_PENDING",
                    "message", "Refund requested while payment is holding");
        }
        if (payment.getPaymentMethod() == PaymentMethod.PAYOS && payment.getPayosOrderCode() != null) {
            Map<String, Object> result = payOsProvider.cancelPayment(payment.getPayosOrderCode(), reason);
            if (PyCompat.truthy(result.get("success"))) {
                stateService.transition(payment, PaymentStatus.CANCELLED, reason, result, "cancel_payment", null, null);
            }
            return result;
        }
        stateService.transition(payment, PaymentStatus.CANCELLED, reason, null, "cancel_payment", null, null);
        return PaymentRefundService.result("success", true, "status", "CANCELLED");
    }

    /**
     * Django {@code cancel_payment_with_refund(payment_uid, reason)}. PORT-NOTE: dead code in
     * Django (no caller anywhere); ported because it is small and money-adjacent. It only asks
     * PayOS to cancel the link and marks REFUND_PENDING — it moves no money.
     */
    public Map<String, Object> cancelPaymentWithRefund(UUID paymentUid, String reason) {
        Optional<PaymentTransaction> found = tx.required(() -> paymentRepository.findByUid(paymentUid));
        if (found.isEmpty()) {
            return PaymentRefundService.result("success", false, "error", "Payment " + paymentUid + " not found");
        }
        PaymentTransaction payment = found.get();
        PaymentTransactionState state = stateService.ensureState(payment);
        if (state.getStatus() == PaymentStatus.REFUNDED) {
            return PaymentRefundService.result("success", true, "skipped", true, "payment_uid", payment.getUid().toString(),
                    "message", "Payment already refunded");
        }
        if (payment.getPaymentMethod() != PaymentMethod.PAYOS) {
            return PaymentRefundService.result("success", false, "error", "Only PayOS payments can be refunded");
        }
        PaymentStatus status = state.getStatus();
        if (status != PaymentStatus.SUCCESS && status != PaymentStatus.HOLDING && status != PaymentStatus.REFUND_PENDING) {
            return PaymentRefundService.result("success", false, "error", "Cannot refund payment with status: " + status);
        }
        if (payment.getPayosOrderCode() == null) {
            return PaymentRefundService.result("success", false, "error", "Missing PayOS order code");
        }
        Map<String, Object> result = payOsProvider.cancelPayment(payment.getPayosOrderCode(),
                reason != null && !reason.isEmpty() ? reason : "Order cancelled, refund requested");
        if (PyCompat.truthy(result.get("success"))) {
            Map<String, Object> gatewayResponse = PaymentRefundService.copyOf(state.getGatewayResponse());
            gatewayResponse.put("refund_info", result);
            gatewayResponse.put("refund_reason", reason);
            gatewayResponse.put("refunded_at", PyCompat.isoformat(Instant.now()));
            stateService.transition(payment, PaymentStatus.REFUND_PENDING, reason, gatewayResponse,
                    "cancel_payment_with_refund", null, null);
            return PaymentRefundService.result("success", true, "message", "Refund initiated successfully",
                    "payment_uid", payment.getUid().toString(), "order_code", payment.getPayosOrderCode(),
                    "amount", payment.getAmount().doubleValue(), "refund_status", result.get("status"));
        }
        return PaymentRefundService.result("success", false,
                "error", result.getOrDefault("error", "Failed to process refund"), "payment_uid", payment.getUid().toString());
    }

    /** Django {@code get_payment_invoices(payment_uid)}. */
    public Map<String, Object> getPaymentInvoices(UUID paymentUid) {
        Optional<PaymentTransaction> found = tx.required(() -> paymentRepository.findByUid(paymentUid));
        if (found.isEmpty()) {
            return PaymentRefundService.result("success", false, "error", "Payment " + paymentUid + " not found");
        }
        PaymentTransaction payment = found.get();
        if (payment.getPaymentMethod() == PaymentMethod.PAYOS && payment.getPayosOrderCode() != null) {
            return payOsProvider.getPaymentInvoices(payment.getPayosOrderCode());
        }
        return PaymentRefundService.result("success", false, "error", "Invoices only available for PayOS payments");
    }

    // =====================================================================
    // handle_payos_webhook
    // =====================================================================

    /**
     * Django {@code handle_payos_webhook(webhook_data)} — called by the {@code payos_webhook}
     * view, which ALWAYS answers HTTP 200 regardless of this result.
     * <ol>
     *   <li>Verify the HMAC ({@link PayOsProvider#verifyWebhookData}). Invalid → if the payload
     *       names a known order code, a forensic {@code WEBHOOK_SIG_INVALID} event is appended to
     *       that payment's audit chain; nothing else changes; code "97".</li>
     *   <li>No order code → "02"; unknown order code → "01".</li>
     *   <li><b>Replay/idempotency guard (Django's, verbatim):</b> state HOLDING → "Already
     *       processed"; CANCELLED/FAILED/REFUNDED → "Already in terminal state". Either way
     *       nothing is written. Other states (RELEASED, SUCCESS, REFUND_PENDING) are NOT
     *       guarded: a replayed success webhook then fails the PENDING-only transition to
     *       HOLDING (code "98"), after writing only an event row — no money or stock moves.</li>
     *   <li>Success → gateway ids copied onto the state, a signature-verified
     *       {@code PAYOS_WEBHOOK} event, then {@link #syncSuccessfulPayment}.</li>
     *   <li>Valid but not successful → CANCELLED (PayOS status CANCELLED) else FAILED, orders
     *       cancelled ({@link #cancelOrdersForPayment}); code "99".</li>
     * </ol>
     * PORT-NOTE (preserved, flagged): the guard in step 3 is a plain read, not under a row lock,
     * so two copies of the same webhook arriving concurrently can both pass it (Django has the
     * same race). Money-wise this cannot double-credit (the only credit on this path is the
     * refund, which re-checks REFUNDED under a lock), but it can double-append audit events,
     * and a forked event chain later makes {@code assert_payment_confirmed} refuse refunds.
     */
    public Map<String, Object> handlePayosWebhook(Object webhookData) {
        try {
            Map<String, Object> verification = payOsProvider.verifyWebhookData(webhookData);
            log.info("Webhook verification result: {}", verification);

            if (!Boolean.TRUE.equals(verification.get("is_valid"))) {
                log.warn("Invalid webhook signature for data: {}", webhookData);
                Object tamperOrderCode = verification.get("order_code");
                if (PyCompat.truthy(tamperOrderCode)) {
                    Optional<PaymentTransaction> tampered = findByOrderCode(tamperOrderCode);
                    tampered.ifPresent(p -> {
                        String raw = PyCompat.str(webhookData);
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("truncated_raw", raw.substring(0, Math.min(500, raw.length())));
                        stateService.recordEvent(p, "WEBHOOK_SIG_INVALID", null, null, payload, false, "payos_webhook");
                    });
                }
                return webhookResult(false, "Invalid signature", "97");
            }

            Object orderCode = verification.get("order_code");
            if (!PyCompat.truthy(orderCode)) {
                log.error("Missing order_code in webhook data: {}", webhookData);
                return webhookResult(false, "Missing order_code", "02");
            }
            Optional<PaymentTransaction> found = findByOrderCode(orderCode);
            if (found.isEmpty()) {
                log.error("Transaction not found for order_code: {}", orderCode);
                return webhookResult(false, "Transaction not found for order_code: " + PyCompat.str(orderCode), "01");
            }
            PaymentTransaction payment = found.get();
            PaymentTransactionState state = stateService.ensureState(payment);

            // Idempotency guard: skip if already in a terminal state
            if (state.getStatus() == PaymentStatus.HOLDING) {
                log.info("Duplicate webhook ignored: payment {} already HOLDING", payment.getUid());
                return webhookResult(true, "Already processed", "00");
            }
            if (state.getStatus() == PaymentStatus.CANCELLED || state.getStatus() == PaymentStatus.FAILED
                    || state.getStatus() == PaymentStatus.REFUNDED) {
                log.info("Late webhook ignored: payment {} already in terminal state {}", payment.getUid(), state.getStatus());
                return webhookResult(true, "Already in terminal state", "00");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> webhookMap = (Map<String, Object>) webhookData;
            if (Boolean.TRUE.equals(verification.get("is_success"))) {
                Object reference = verification.get("reference");
                Object linkId = verification.get("payment_link_id");
                if (PyCompat.truthy(reference) && !PyCompat.str(reference).equals(state.getTransactionId())) {
                    state.setTransactionId(PyCompat.str(reference));
                }
                if (!PyCompat.truthy(state.getPayosPaymentLinkId()) && PyCompat.truthy(linkId)) {
                    state.setPayosPaymentLinkId(PyCompat.str(linkId));
                }
                if (PyCompat.truthy(reference) || PyCompat.truthy(linkId)) {
                    state = stateService.save(state);
                }
                stateService.recordEvent(payment, "PAYOS_WEBHOOK", state.getStatus().name(), PaymentStatus.HOLDING.name(),
                        webhookMap, true, "payos_webhook");
                SyncResult sync = syncSuccessfulPayment(payment, webhookMap, "payos_webhook");
                log.info("Payment {} marked as HOLDING, orders CONFIRMED_SYSTEM, vouchers USED={}, split planned for {} chef(s)",
                        payment.getUid(), sync.vouchersUsed(), sync.splitSummary().get("chef_count"));
                return webhookResult(true, "Payment held in escrow", "00");
            }

            Object data = webhookMap.get("data");
            Object gatewayStatus = data instanceof Map<?, ?> m ? m.get("status") : null;
            PaymentStatus newStatus = "CANCELLED".equals(gatewayStatus) ? PaymentStatus.CANCELLED : PaymentStatus.FAILED;
            String label = PyCompat.truthy(gatewayStatus) ? PyCompat.str(gatewayStatus) : "failed";
            stateService.transition(payment, newStatus, "payos_" + label, webhookMap, "payos_webhook", true, null);
            cancelOrdersForPayment(payment, "PayOS payment " + label);
            log.info("Payment {} marked as {} and orders cancelled", payment.getUid(), newStatus);
            return webhookResult(false, "Payment failed", "99");
        } catch (RuntimeException ex) {
            log.error("Error processing webhook: {}", ex.toString(), ex);
            return webhookResult(false, String.valueOf(ex.getMessage()), "98");
        }
    }

    /** Django {@code PaymentTransaction.objects.get(payos_order_code=order_code)} — coerces like BigIntegerField. */
    private Optional<PaymentTransaction> findByOrderCode(Object orderCode) {
        long code = PyCompat.toLong(orderCode);
        return tx.required(() -> paymentRepository.findByPayosOrderCode(code));
    }

    private static Map<String, Object> webhookResult(boolean success, String message, String code) {
        return PaymentRefundService.result("success", success, "message", message, "code", code);
    }

    // =====================================================================
    // _sync_successful_payment
    // =====================================================================

    public record SyncResult(int ordersCount, int vouchersUsed, Map<String, Object> splitSummary) {
    }

    /** Django's three-valued {@code confirm()} outcome the recovery logic is written against. */
    enum ConfirmOutcome { CONFIRMED, ALREADY_CONFIRMED, NOT_RESERVED }

    /**
     * Django {@code _sync_successful_payment(payment, gateway_payload, source)} — the moment
     * money is recognized as held in ESCROW:
     * <ol>
     *   <li>state → HOLDING ({@code paid_at = now}); if already HOLDING only the gateway payload
     *       is refreshed;</li>
     *   <li>EVERY order of the checkout → CONFIRMED_SYSTEM / payment_status HOLDING (bulk);</li>
     *   <li>per order, per item: finalize the stock hold ({@code confirm()}); if the hold is gone
     *       (the TTL sweep expired it before a late webhook) try a fresh {@code reserve()} +
     *       {@code confirm()}; if that is impossible (sold out) the order is refunded
     *       ({@link PaymentRefundService}), CANCELLED and the customer notified
     *       {@code CANCELLED_REFUNDED_LATE_PAYMENT}; otherwise {@code CONFIRMED} is notified.
     *       This whole block is best-effort: any exception is logged and the payment stays
     *       HOLDING ("the sale is already legally final");</li>
     *   <li>the checkout's RESERVED vouchers → USED; refunded orders' vouchers → CANCELLED;</li>
     *   <li>a payout split plan (10% platform fee per order) is stored under
     *       {@code gateway_response["settlement"]} — planning only, no money moves.</li>
     * </ol>
     *
     * <p><b>DJANGO BUG, FIXED BY DEFAULT — read {@link PaymentProperties#isPreserveDjangoConfirmOutcomeBug()}.</b>
     * Django's {@code stock_reservation.confirm()} returns a bool, but this code tests
     * {@code outcome in ("confirmed", "already_confirmed")}; the test is never true, so in Django
     * EVERY paid order goes through the recovery branch, whose {@code reserve()} refuses the
     * just-confirmed hold, and the order ends CANCELLED with the customer refunded to their
     * internal wallet. The flag now defaults to the intended behavior (user decision, open
     * question #1); setting it to {@code true} reproduces Django's bug.
     */
    public SyncResult syncSuccessfulPayment(PaymentTransaction payment, Map<String, Object> gatewayPayload, String source) {
        PaymentTransactionState state = stateService.ensureState(payment);
        if (state.getStatus() != PaymentStatus.HOLDING) {
            // Post-port hardening (2026-09-25, found by PaymentReconciliationTest): claim the
            // PENDING -> HOLDING transition under a payment row lock, re-reading the state after
            // the lock is acquired. Two racers (webhook + reconciliation job / return URL) used
            // to both pass the unlocked guards and both record STATE_CHANGED -> HOLDING; now the
            // loser sees HOLDING and only refreshes the payload. A rejected transition still
            // commits its STATE_REJECTED event and is rethrown afterwards (Django save-then-raise).
            PaymentValueError[] rejected = new PaymentValueError[1];
            tx.required(() -> {
                paymentRepository.findForUpdateById(payment.getId());
                PaymentTransactionState current = stateService.ensureState(payment);
                if (current.getStatus() == PaymentStatus.HOLDING) {
                    if (gatewayPayload != null) {
                        current.setGatewayResponse(gatewayPayload);
                        stateService.save(current);
                    }
                    return;
                }
                try {
                    stateService.transition(payment, PaymentStatus.HOLDING, "payos_confirmed", gatewayPayload, source,
                            null, Instant.now());
                } catch (PaymentValueError ex) {
                    rejected[0] = ex;
                }
            });
            if (rejected[0] != null) {
                throw rejected[0];
            }
        } else if (gatewayPayload != null) {
            state.setGatewayResponse(gatewayPayload);
            stateService.save(state);
        }

        UUID checkoutUid = payment.getCheckoutUid();
        tx.required(() -> orderRepository.updateAllOfCheckout(checkoutUid, OrderStatus.CONFIRMED_SYSTEM, PaymentStatus.HOLDING));

        Set<UUID> refundedOrderUids = new LinkedHashSet<>();
        try {
            List<Order> orders = tx.required(() -> orderRepository.findCheckoutOrders(checkoutUid));
            for (Order order : orders) {
                confirmOrderStock(order, refundedOrderUids);
            }
        } catch (RuntimeException ex) {
            log.error("Stock confirm/notify failed for checkout {}: {}", checkoutUid, ex.toString());
        }

        int updatedCount = tx.required(() -> appliedVoucherRepository.markReservedUsedForCheckout(checkoutUid,
                VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED));
        if (!refundedOrderUids.isEmpty()) {
            tx.required(() -> appliedVoucherRepository.updateStatusForOrders(refundedOrderUids,
                    VoucherReservationStatus.CANCELLED));
        }

        // NOTE (Django's own): the split summary does not exclude orders refunded above.
        Map<String, Object> splitSummary = buildCheckoutSplitSummary(checkoutUid);
        PaymentTransactionState fresh = stateService.ensureState(payment);
        Map<String, Object> gatewayResponse = PaymentRefundService.copyOf(fresh.getGatewayResponse());
        Object existingSettlement = gatewayResponse.get("settlement");
        @SuppressWarnings("unchecked")
        Map<String, Object> settlement = existingSettlement instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        settlement.put("status", "HOLDING");
        settlement.put("source", source);
        settlement.put("split_summary", splitSummary);
        settlement.put("updated_at", PyCompat.isoformat(Instant.now()));
        gatewayResponse.put("settlement", settlement);
        fresh.setGatewayResponse(gatewayResponse);
        stateService.save(fresh);

        int ordersCount = tx.required(() -> orderRepository.findCheckoutOrders(checkoutUid).size());
        return new SyncResult(ordersCount, updatedCount, splitSummary);
    }

    private void confirmOrderStock(Order order, Set<UUID> refundedOrderUids) {
        List<OrderItem> recoveredItems = new ArrayList<>();
        boolean orderNeedsRefund = false;
        List<OrderItem> items = new ArrayList<>(order.getItems());
        items.sort(Comparator.comparing(OrderItem::getId)); // Django: default pk order
        var deliveryDate = order.getCheckout().getDeliveryDate();

        for (OrderItem item : items) {
            boolean confirmed = stockReservationService.confirm(item.getId(), item.getDish(), deliveryDate, item.getQuantity());
            ConfirmOutcome outcome = interpretConfirm(item.getId(), confirmed);
            if (outcome == ConfirmOutcome.CONFIRMED || outcome == ConfirmOutcome.ALREADY_CONFIRMED) {
                continue;
            }
            // outcome == "not_reserved": try to recover with a fresh hold.
            try {
                stockReservationService.reserve(item.getId(), order.getUid(), item.getDish(), deliveryDate, item.getQuantity());
                stockReservationService.confirm(item.getId(), item.getDish(), deliveryDate, item.getQuantity());
                recoveredItems.add(item);
            } catch (StockReservationException ex) {
                // Genuinely out of stock now (Django: except ValueError) — all-or-nothing per order.
                orderNeedsRefund = true;
                break;
            }
        }

        if (orderNeedsRefund) {
            for (OrderItem recovered : recoveredItems) {
                stockReservationService.release(recovered.getId(), recovered.getDish(), deliveryDate,
                        recovered.getQuantity(), false);
            }
            Map<String, Object> refund = refundService.handleOrderCancellationRefund(order.getUid(),
                    "Payment webhook arrived after the stock hold expired, "
                            + "and the item sold out before the order could be recovered");
            if (!PyCompat.truthy(refund.get("success"))) {
                log.error("Refund failed for order {} after late-webhook stock recovery could not be completed: {}",
                        order.getUid(), refund.get("error"));
            }
            // Outbox, not a direct async call (backend-edit 9939179): enqueued in the SAME
            // transaction as the cancellation, published after commit; a failed hand-off must not
            // abort confirming the remaining orders of this checkout.
            tx.required(() -> {
                orderRepository.updateStatusOnly(order.getUid(), OrderStatus.CANCELLED);
                outboxService.enqueueOrderNotification("order-late-payment-refunded:" + order.getUid(),
                        order.getUid(), "CANCELLED_REFUNDED_LATE_PAYMENT");
            });
            refundedOrderUids.add(order.getUid());
        } else {
            outboxService.enqueueOrderNotification("order-confirmed:" + order.getUid(), order.getUid(), "CONFIRMED");
        }
    }

    /**
     * Maps {@code confirm()}'s boolean to the outcome Django's caller compares against.
     * Faithful mode: Django compares a bool to strings, so the answer is always "not
     * CONFIRMED/ALREADY_CONFIRMED". Intended mode: true → CONFIRMED; false → look at the ledger
     * row (CONFIRMED → ALREADY_CONFIRMED, anything else → NOT_RESERVED).
     */
    ConfirmOutcome interpretConfirm(long itemId, boolean confirmed) {
        if (properties.isPreserveDjangoConfirmOutcomeBug()) {
            return ConfirmOutcome.NOT_RESERVED;
        }
        if (confirmed) {
            return ConfirmOutcome.CONFIRMED;
        }
        StockReservationStatus current = tx.required(() -> stockReservationRepository.findByOrderItemId(itemId)
                .map(StockReservation::getStatus).orElse(null));
        return current == StockReservationStatus.CONFIRMED ? ConfirmOutcome.ALREADY_CONFIRMED : ConfirmOutcome.NOT_RESERVED;
    }

    // =====================================================================
    // _cancel_orders_for_payment / _build_checkout_split_summary
    // =====================================================================

    /**
     * Django {@code _cancel_orders_for_payment(payment, reason)} (one atomic block): the
     * checkout's/orders' RESERVED+USED vouchers → CANCELLED; every not-yet-cancelled order →
     * CANCELLED with the payment's current status, and each item's stock hold released through
     * the ledger ({@code stock_reservation.release(...)}, explicit cancel).
     *
     * <p>backend-edit 9939179 fixed the old Django code (and this port followed): it used to add
     * {@code quantity} straight onto {@code DishAvailability}, but an unpaid PayOS order's hold
     * was never deducted there (that only happens at confirm()), so it inflated stock — and left
     * the RESERVED row and the Redis counter holding stock for a cancelled order. release() is
     * idempotent and a no-op for holds the sweep already released.
     */
    public void cancelOrdersForPayment(PaymentTransaction payment, String reason) {
        UUID checkoutUid = payment.getCheckoutUid();
        tx.required(() -> {
            List<Order> orders = orderRepository.findCheckoutOrders(checkoutUid);
            if (orders.isEmpty()) {
                return;
            }
            List<UUID> orderUids = orders.stream().map(Order::getUid).toList();
            List<Object[]> work = new ArrayList<>();
            for (Order order : orders) {
                if (order.getStatus() == OrderStatus.CANCELLED) {
                    continue;
                }
                List<OrderItem> items = new ArrayList<>(order.getItems());
                items.sort(Comparator.comparing(OrderItem::getId));
                work.add(new Object[]{order.getUid(), items, order.getCheckout().getDeliveryDate()});
            }
            appliedVoucherRepository.updateStatusForCheckoutOrOrders(checkoutUid, orderUids,
                    List.of(VoucherReservationStatus.RESERVED, VoucherReservationStatus.USED),
                    VoucherReservationStatus.CANCELLED);
            for (Object[] w : work) {
                @SuppressWarnings("unchecked")
                List<OrderItem> items = (List<OrderItem>) w[1];
                for (OrderItem item : items) {
                    stockReservationService.release(item.getId(), item.getDish(), (java.time.LocalDate) w[2],
                            item.getQuantity(), false);
                }
                PaymentTransactionState state = stateService.ensureState(payment);
                orderRepository.updateStatusAndPaymentStatus((UUID) w[0], OrderStatus.CANCELLED, state.getStatus());
            }
            log.info("[Payment] Cancelled orders for payment {}. Reason: {}", payment.getUid(), reason);
        });
    }

    /** Django {@code _build_checkout_split_summary(checkout)} — 10% platform fee per order, HALF_UP to whole VND. */
    public Map<String, Object> buildCheckoutSplitSummary(UUID checkoutUid) {
        return tx.required(() -> {
            List<Order> orders = orderRepository.findCheckoutOrders(checkoutUid);
            List<Map<String, Object>> splitItems = new ArrayList<>();
            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;
            BigDecimal totalChef = BigDecimal.ZERO;
            Set<Long> chefIds = new HashSet<>();
            for (Order order : orders) {
                BigDecimal gross = WalletService.quantize(order.getTotalPrice() != null ? order.getTotalPrice() : BigDecimal.ZERO);
                BigDecimal fee = WalletService.quantize(gross.multiply(PLATFORM_FEE_PERCENT));
                BigDecimal chefPayout = WalletService.quantize(gross.subtract(fee));
                totalGross = totalGross.add(gross);
                totalFee = totalFee.add(fee);
                totalChef = totalChef.add(chefPayout);
                CustomUser chef = order.getChef();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("order_uid", order.getUid().toString());
                item.put("chef_id", chef != null ? chef.getId() : null);
                item.put("chef_name", chef != null ? fullName(chef) : null);
                item.put("chef_email", chef != null ? chef.getEmail() : null);
                item.put("gross_amount", gross.doubleValue());
                item.put("platform_fee_percent", PLATFORM_FEE_PERCENT.doubleValue());
                item.put("platform_fee_amount", fee.doubleValue());
                item.put("chef_payout_amount", chefPayout.doubleValue());
                item.put("status", "PLANNED");
                splitItems.add(item);
                if (chef != null) {
                    chefIds.add(chef.getId());
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("checkout_uid", checkoutUid.toString());
            summary.put("order_count", orders.size());
            summary.put("chef_count", chefIds.size());
            summary.put("gross_amount", totalGross.doubleValue());
            summary.put("platform_fee_amount", totalFee.doubleValue());
            summary.put("chef_payout_amount", totalChef.doubleValue());
            summary.put("split_items", splitItems);
            summary.put("created_at", PyCompat.isoformat(Instant.now()));
            return summary;
        });
    }

    /** Django {@code user.get_full_name()}: {@code f"{first_name} {last_name}".strip()}. */
    static String fullName(CustomUser user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        return (first + " " + last).strip();
    }
}
