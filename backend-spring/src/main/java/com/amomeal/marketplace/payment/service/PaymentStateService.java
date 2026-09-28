package com.amomeal.marketplace.payment.service;

import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import com.amomeal.marketplace.payment.entity.PaymentTransactionEvent;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import com.amomeal.marketplace.payment.provider.PayOsProvider;
import com.amomeal.marketplace.payment.provider.PyCompat;
import com.amomeal.marketplace.payment.repository.PaymentTransactionEventRepository;
import com.amomeal.marketplace.payment.repository.PaymentTransactionStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.amomeal.marketplace.order.entity.PaymentStatus.*;

/**
 * Port of the payment-state half of ../../backend/payment/services.py::PaymentService:
 * {@code _ensure_payment_state}, {@code _record_payment_event}, {@code _transition_payment_state},
 * {@code set_payment_state}, {@code assert_payment_confirmed}, plus
 * {@code PaymentTransactionEvent.verify_event_chain} from models.py.
 *
 * <p><b>Transactions.</b> Each method is one Django statement group run through
 * {@link PaymentTx#required}: inside a caller's transaction it joins it (Django: inside the
 * caller's {@code atomic}), otherwise it commits on its own (Django: autocommit).
 * {@link #transition} writes its {@code STATE_REJECTED} event and only THEN throws, OUTSIDE the
 * template — so under autocommit the rejection event is durable exactly like Django's
 * save-then-raise, and inside a caller's transaction the caller's own fate decides.
 */
@Service
@RequiredArgsConstructor
public class PaymentStateService {

    /** Django {@code PaymentService.__init__._allowed_transitions}, verbatim. */
    static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PENDING, EnumSet.of(HOLDING, SUCCESS, FAILED, CANCELLED));
        ALLOWED_TRANSITIONS.put(HOLDING, EnumSet.of(RELEASED, REFUND_PENDING, REFUNDED, FAILED, CANCELLED));
        ALLOWED_TRANSITIONS.put(SUCCESS, EnumSet.of(REFUND_PENDING, REFUNDED, CANCELLED));
        ALLOWED_TRANSITIONS.put(REFUND_PENDING, EnumSet.of(REFUNDED, CANCELLED));
        ALLOWED_TRANSITIONS.put(RELEASED, EnumSet.of(REFUND_PENDING, REFUNDED));
        ALLOWED_TRANSITIONS.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    private final PaymentTransactionStateRepository stateRepository;
    private final PaymentTransactionEventRepository eventRepository;
    private final PaymentHmac hmac;
    private final PaymentTx tx;
    private final PayOsProvider payOsProvider;

    // =====================================================================
    // _ensure_payment_state
    // =====================================================================

    public PaymentTransactionState ensureState(PaymentTransaction payment) {
        return ensureState(payment, PENDING, null, null);
    }

    /**
     * Django {@code _ensure_payment_state}: {@code get_or_create} the state row with the given
     * defaults, recording a {@code STATE_INIT} event only when it was created.
     */
    public PaymentTransactionState ensureState(PaymentTransaction payment, PaymentStatus status, Instant paidAt,
                                               Map<String, Object> gatewayResponse) {
        return tx.required(() -> stateRepository.findByPaymentTransactionId(payment.getId()).orElseGet(() -> {
            PaymentTransactionState state = stateRepository.saveAndFlush(PaymentTransactionState.builder()
                    .paymentTransactionId(payment.getId())
                    .status(status)
                    .paidAt(paidAt)
                    .gatewayResponse(gatewayResponse)
                    .build());
            recordEvent(payment, "STATE_INIT", null, state.getStatus().name(), gatewayResponse, null, "system");
            return state;
        }));
    }

    /** Django {@code getattr(payment, "state", None)} — a read that never creates the row. */
    public java.util.Optional<PaymentTransactionState> findState(PaymentTransaction payment) {
        return tx.required(() -> stateRepository.findByPaymentTransactionId(payment.getId()));
    }

    /** Django {@code state.save(update_fields=[...])} for the non-status fields. */
    public PaymentTransactionState save(PaymentTransactionState state) {
        return tx.required(() -> stateRepository.saveAndFlush(state));
    }

    // =====================================================================
    // _record_payment_event
    // =====================================================================

    /**
     * Django {@code _record_payment_event}: previous hash = the payment's last event's
     * {@code chain_hash} (genesis {@code "0"*64}), then
     * {@code HMAC(f"{payment_id}:{event_type}:{status_from}:{status_to}:{previous_hash}")}.
     *
     * <p>PORT-NOTE (preserved, flagged): not serialized per payment — two concurrent writers for
     * the same payment can both read the same "last" hash and fork the chain; Django has the
     * same race (no lock). A forked chain makes {@link #assertPaymentConfirmed} refuse refunds.
     */
    public PaymentTransactionEvent recordEvent(PaymentTransaction payment, String eventType, String statusFrom,
                                               String statusTo, Map<String, Object> payload, Boolean signatureValid,
                                               String source) {
        return tx.required(() -> {
            String previousHash = eventRepository.findFirstByPaymentTransactionIdOrderByCreatedAtDescIdDesc(payment.getId())
                    .map(PaymentTransactionEvent::getChainHash)
                    .filter(h -> !h.isEmpty())
                    .orElse(PaymentTransactionEvent.GENESIS_HASH);
            String chainHash = hmac.paymentEventChainHash(payment.getId(), eventType, statusFrom, statusTo, previousHash);
            return eventRepository.saveAndFlush(PaymentTransactionEvent.builder()
                    .paymentTransactionId(payment.getId())
                    .eventType(eventType)
                    .statusFrom(statusFrom)
                    .statusTo(statusTo)
                    .payload(payload)
                    .signatureValid(signatureValid)
                    .source(source)
                    .previousHash(previousHash)
                    .chainHash(chainHash)
                    .build());
        });
    }

    // =====================================================================
    // _transition_payment_state / set_payment_state
    // =====================================================================

    /** Django {@code set_payment_state(payment, new_status, reason, gateway_payload, source, paid_at)}. */
    public PaymentTransactionState setPaymentState(PaymentTransaction payment, PaymentStatus newStatus, String reason,
                                                   Map<String, Object> gatewayPayload, String source, Instant paidAt) {
        return transition(payment, newStatus, reason, gatewayPayload, source, null, paidAt);
    }

    private record TransitionOutcome(PaymentTransactionState state, PaymentStatus rejectedFrom) {
    }

    /**
     * Django {@code _transition_payment_state(..., allow_same=True)}:
     * <ul>
     *   <li>same status → {@code STATE_NOOP} event, nothing else;</li>
     *   <li>not in {@link #ALLOWED_TRANSITIONS} → {@code STATE_REJECTED} event, then
     *       {@code ValueError("Invalid payment status transition: A -> B")};</li>
     *   <li>otherwise status (+ paid_at / gateway_response when given) saved, then a
     *       {@code STATE_CHANGED} event.</li>
     * </ul>
     * Event payload: {@code {"reason": ..., "gateway_payload": ...}}.
     */
    public PaymentTransactionState transition(PaymentTransaction payment, PaymentStatus newStatus, String reason,
                                              Map<String, Object> gatewayPayload, String source, Boolean signatureValid,
                                              Instant paidAt) {
        TransitionOutcome outcome = tx.required(() -> {
            PaymentTransactionState state = ensureState(payment);
            PaymentStatus oldStatus = state.getStatus();
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("reason", reason);
            eventPayload.put("gateway_payload", gatewayPayload);

            if (newStatus == oldStatus) {
                recordEvent(payment, "STATE_NOOP", oldStatus.name(), newStatus.name(), eventPayload, signatureValid, source);
                return new TransitionOutcome(state, null);
            }
            if (!ALLOWED_TRANSITIONS.getOrDefault(oldStatus, Set.of()).contains(newStatus)) {
                recordEvent(payment, "STATE_REJECTED", oldStatus.name(), newStatus.name(), eventPayload, signatureValid, source);
                return new TransitionOutcome(state, oldStatus);
            }
            state.setStatus(newStatus);
            if (paidAt != null) {
                state.setPaidAt(paidAt);
            }
            if (gatewayPayload != null) {
                state.setGatewayResponse(gatewayPayload);
            }
            PaymentTransactionState saved = stateRepository.saveAndFlush(state);
            recordEvent(payment, "STATE_CHANGED", oldStatus.name(), newStatus.name(), eventPayload, signatureValid, source);
            return new TransitionOutcome(saved, null);
        });
        if (outcome.rejectedFrom() != null) {
            throw new PaymentValueError("Invalid payment status transition: " + outcome.rejectedFrom() + " -> " + newStatus);
        }
        return outcome.state();
    }

    // =====================================================================
    // assert_payment_confirmed
    // =====================================================================

    /**
     * Django {@code assert_payment_confirmed(payment, reconcile_with_gateway=False)} — the gate
     * in front of every refund:
     * <ol>
     *   <li>state is HOLDING/SUCCESS/REFUND_PENDING/REFUNDED;</li>
     *   <li>audit evidence: a {@code signature_valid=True} event, OR a
     *       {@code RECONCILIATION_CONFIRMED} event, OR the payment is COD;</li>
     *   <li>the event hash chain verifies;</li>
     *   <li>(optional) PayOS says PAID.</li>
     * </ol>
     *
     * @throws PaymentValueError naming the failed layer
     */
    public void assertPaymentConfirmed(PaymentTransaction payment, boolean reconcileWithGateway) {
        PaymentTransactionState state = ensureState(payment);
        Set<PaymentStatus> confirmed = EnumSet.of(HOLDING, SUCCESS, REFUND_PENDING, REFUNDED);
        if (!confirmed.contains(state.getStatus())) {
            throw new PaymentValueError("Payment " + payment.getUid() + " not confirmed: status=" + state.getStatus());
        }
        boolean hasWebhookVerified = eventRepository.existsByPaymentTransactionIdAndSignatureValidTrue(payment.getId());
        boolean hasReconciled = eventRepository.existsByPaymentTransactionIdAndEventType(payment.getId(),
                "RECONCILIATION_CONFIRMED");
        boolean isCod = payment.getPaymentMethod() == PaymentMethod.COD;
        if (!(hasWebhookVerified || hasReconciled || isCod)) {
            throw new PaymentValueError("Payment " + payment.getUid() + " has no audit evidence of confirmation "
                    + "(no webhook signature nor reconciliation event)");
        }
        ChainVerification chain = verifyEventChain(payment.getId());
        if (!chain.valid()) {
            throw new PaymentValueError("Payment " + payment.getUid() + " audit chain tampered at event_id="
                    + chain.tamperedAt() + " (" + chain.reason() + ")");
        }
        if (reconcileWithGateway && payment.getPayosOrderCode() != null) {
            Map<String, Object> info = payOsProvider.getPaymentInfo(payment.getPayosOrderCode());
            if (!PyCompat.truthy(info.get("success")) || !"PAID".equals(info.get("status"))) {
                throw new PaymentValueError("Payment " + payment.getUid() + " gateway reconciliation failed: "
                        + "gateway_status=" + PyCompat.str(info.get("status")));
            }
        }
    }

    // =====================================================================
    // PaymentTransactionEvent.verify_event_chain
    // =====================================================================

    public record ChainVerification(boolean valid, int eventsChecked, Long tamperedAt, String reason) {
    }

    /** Django {@code PaymentTransactionEvent.verify_event_chain(payment_id)}. */
    public ChainVerification verifyEventChain(Long paymentId) {
        List<PaymentTransactionEvent> events = tx.required(() ->
                eventRepository.findByPaymentTransactionIdOrderByCreatedAtAscIdAsc(paymentId));
        if (events.isEmpty()) {
            return new ChainVerification(true, 0, null, null);
        }
        String expectedPrev = PaymentTransactionEvent.GENESIS_HASH;
        for (int idx = 0; idx < events.size(); idx++) {
            PaymentTransactionEvent ev = events.get(idx);
            if (!ev.getPreviousHash().equals(expectedPrev)) {
                return new ChainVerification(false, idx, ev.getId(), "previous_hash_mismatch");
            }
            String computed = hmac.paymentEventChainHash(paymentId, ev.getEventType(), ev.getStatusFrom(),
                    ev.getStatusTo(), ev.getPreviousHash());
            if (!ev.getChainHash().equals(computed)) {
                return new ChainVerification(false, idx, ev.getId(), "chain_hash_mismatch");
            }
            expectedPrev = ev.getChainHash();
        }
        return new ChainVerification(true, events.size(), null, null);
    }
}
