package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 1:1 port of ../../backend/order/services/order_state_machine.py::OrderStateMachine,
 * including its class docstring:
 *
 * <pre>
 * COD flow:
 *   DRAFT -&gt; PENDING -&gt; CONFIRMED_SHOP -&gt; PROCESSING -&gt; DELIVERING -&gt; COMPLETED
 * PayOS escrow flow:
 *   DRAFT -&gt; CONFIRMED_SYSTEM -&gt; CONFIRMED_SHOP -&gt; PROCESSING -&gt; DELIVERING
 *         -&gt; COMPLETED -&gt; [RELEASE]
 * </pre>
 *
 * <p>Note the PayOS flow's real runtime path goes DRAFT -&gt; PENDING (set by
 * {@code place_order}) and only then PENDING/whatever -&gt; CONFIRMED_SYSTEM,
 * written by the payment webhook with an unconditional bulk
 * {@code orders.update(status=CONFIRMED_SYSTEM)} that <b>bypasses this state
 * machine entirely</b> (see ../../backend/ORDER_INVENTORY_FLOW.md §T3/T3'). That
 * is why {@code PENDING -> CONFIRMED_SYSTEM} is not listed as a valid transition
 * here even though it happens in practice — faithfully preserved, not "fixed".
 *
 * <p>Pure static logic, no Spring bean — exactly like the Django classmethods.
 */
public final class OrderStateMachine {

    private OrderStateMachine() {
    }

    /** Django: {@code VALID_TRANSITIONS}. */
    private static final Map<OrderStatus, List<OrderStatus>> VALID_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        VALID_TRANSITIONS.put(OrderStatus.DRAFT, List.of(
                OrderStatus.PENDING,           // COD place order
                OrderStatus.CONFIRMED_SYSTEM,  // PayOS payment success
                OrderStatus.CANCELLED));       // cancel before payment
        VALID_TRANSITIONS.put(OrderStatus.PENDING, List.of(
                OrderStatus.CONFIRMED_SHOP,    // shop confirms COD order
                OrderStatus.CANCELLED));
        VALID_TRANSITIONS.put(OrderStatus.CONFIRMED_SYSTEM, List.of(
                OrderStatus.CONFIRMED_SHOP,    // shop confirms PayOS order
                OrderStatus.CANCELLED));       // requires refund
        VALID_TRANSITIONS.put(OrderStatus.CONFIRMED_SHOP, List.of(
                OrderStatus.PROCESSING,        // chef starts cooking
                OrderStatus.CANCELLED));       // system/admin cancel only (not chef/customer)
        VALID_TRANSITIONS.put(OrderStatus.PROCESSING, List.of(
                OrderStatus.DELIVERING,
                OrderStatus.CANCELLED));       // emergency cancel (requires refund if paid)
        VALID_TRANSITIONS.put(OrderStatus.DELIVERING, List.of(
                OrderStatus.COMPLETED,
                OrderStatus.CANCELLED));       // delivery failed (requires refund if paid)
        VALID_TRANSITIONS.put(OrderStatus.COMPLETED, List.of());   // final
        VALID_TRANSITIONS.put(OrderStatus.CANCELLED, List.of());   // final
    }

    /** Django: {@code REFUND_REQUIRED_STATUSES} (CONFIRMED_SHOP deliberately absent). */
    private static final List<OrderStatus> REFUND_REQUIRED_STATUSES = List.of(
            OrderStatus.CONFIRMED_SYSTEM,
            OrderStatus.PROCESSING,
            OrderStatus.DELIVERING);

    /** Django: {@code can_transition}. */
    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return getNextValidStatuses(from).contains(to);
    }

    /** Django: {@code get_next_valid_statuses}. */
    public static List<OrderStatus> getNextValidStatuses(OrderStatus current) {
        return VALID_TRANSITIONS.getOrDefault(current, List.of());
    }

    /**
     * Django: {@code validate_transition} -&gt; {@code (is_valid, error_message)}.
     * Note the same-status case is rejected FIRST with its own message, before the
     * transition table is consulted.
     */
    public static ValidationOutcome validateTransition(OrderStatus from, OrderStatus to) {
        if (from == to) {
            return new ValidationOutcome(false, "Order is already in " + to + " status");
        }
        if (!canTransition(from, to)) {
            return new ValidationOutcome(false, "Cannot transition from " + from + " to " + to
                    + ". Valid transitions: " + getNextValidStatuses(from));
        }
        return new ValidationOutcome(true, "");
    }

    /**
     * Django: {@code requires_refund} — PayOS + money actually sitting in escrow
     * (HOLDING, not SUCCESS) + a status from {@link #REFUND_REQUIRED_STATUSES}.
     */
    public static boolean requiresRefund(OrderStatus currentStatus, PaymentMethod paymentMethod,
                                         PaymentStatus paymentStatus) {
        return paymentMethod == PaymentMethod.PAYOS
                && paymentStatus == PaymentStatus.HOLDING
                && REFUND_REQUIRED_STATUSES.contains(currentStatus);
    }

    /** Django: {@code can_customer_cancel} — only before the chef has accepted. */
    public static boolean canCustomerCancel(OrderStatus status) {
        return status == OrderStatus.DRAFT
                || status == OrderStatus.PENDING
                || status == OrderStatus.CONFIRMED_SYSTEM;
    }

    /**
     * Django: {@code can_chef_decide} — PENDING (COD) or CONFIRMED_SYSTEM (PayOS).
     *
     * <p>PORT-NOTE: {@code chef_confirm_order} does NOT actually call this — the
     * call is commented out in Django and replaced by an explicit
     * payment-method-vs-status pair check. It <i>is</i> still the documented rule
     * and is mirrored by {@code cancel_order}'s chef branch, so the helper is
     * ported and tested.
     */
    public static boolean canChefDecide(OrderStatus status) {
        return status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED_SYSTEM;
    }

    /**
     * Django: {@code should_trigger_payout} — payout happens after RELEASED, not
     * while the money is still HOLDING in escrow.
     */
    public static boolean shouldTriggerPayout(OrderStatus orderStatus, PaymentStatus paymentStatus,
                                              boolean isRefunded) {
        return orderStatus == OrderStatus.COMPLETED
                && paymentStatus == PaymentStatus.RELEASED
                && !isRefunded;
    }

    /** Django: {@code can_release_funds} — the escrow-release step, separate from payout. */
    public static boolean canReleaseFunds(OrderStatus orderStatus, PaymentStatus paymentStatus) {
        return orderStatus == OrderStatus.COMPLETED && paymentStatus == PaymentStatus.HOLDING;
    }

    /** Django's {@code tuple[bool, str]} return of {@code validate_transition}. */
    public record ValidationOutcome(boolean valid, String errorMessage) {
    }
}
