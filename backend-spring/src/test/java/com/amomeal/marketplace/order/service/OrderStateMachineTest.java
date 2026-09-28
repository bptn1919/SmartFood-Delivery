package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.amomeal.marketplace.order.entity.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive check of ../../backend/order/services/order_state_machine.py —
 * every one of the 8x8 (from, to) pairs is asserted against the transition table
 * transcribed independently here from the Django source, so a typo in either copy
 * shows up as a failure.
 */
class OrderStateMachineTest {

    /** Independent transcription of Django's VALID_TRANSITIONS. */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            DRAFT, EnumSet.of(PENDING, CONFIRMED_SYSTEM, CANCELLED),
            PENDING, EnumSet.of(CONFIRMED_SHOP, CANCELLED),
            CONFIRMED_SYSTEM, EnumSet.of(CONFIRMED_SHOP, CANCELLED),
            CONFIRMED_SHOP, EnumSet.of(PROCESSING, CANCELLED),
            PROCESSING, EnumSet.of(DELIVERING, CANCELLED),
            DELIVERING, EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPair_matchesDjangosTransitionTable(OrderStatus from, OrderStatus to) {
        boolean expected = EXPECTED.get(from).contains(to);
        assertThat(OrderStateMachine.canTransition(from, to)).isEqualTo(expected);

        OrderStateMachine.ValidationOutcome outcome = OrderStateMachine.validateTransition(from, to);
        assertThat(outcome.valid()).isEqualTo(expected);
        if (from == to) {
            assertThat(outcome.errorMessage()).isEqualTo("Order is already in " + to + " status");
        } else if (!expected) {
            assertThat(outcome.errorMessage())
                    .startsWith("Cannot transition from " + from + " to " + to + ". Valid transitions: ");
        } else {
            assertThat(outcome.errorMessage()).isEmpty();
        }
    }

    @Test
    void pendingToConfirmedSystem_isNotInTheTable_eventhoughThePaymentWebhookDoesIt() {
        // Preserved Django quirk: the webhook's bulk update bypasses the state machine.
        assertThat(OrderStateMachine.canTransition(PENDING, CONFIRMED_SYSTEM)).isFalse();
    }

    @Test
    void terminalStates_haveNoSuccessors() {
        assertThat(OrderStateMachine.getNextValidStatuses(COMPLETED)).isEmpty();
        assertThat(OrderStateMachine.getNextValidStatuses(CANCELLED)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void customerCancel_onlyBeforeTheChefAccepts(OrderStatus status) {
        assertThat(OrderStateMachine.canCustomerCancel(status))
                .isEqualTo(status == DRAFT || status == PENDING || status == CONFIRMED_SYSTEM);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void chefDecide_onlyAtPendingOrConfirmedSystem(OrderStatus status) {
        assertThat(OrderStateMachine.canChefDecide(status))
                .isEqualTo(status == PENDING || status == CONFIRMED_SYSTEM);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void requiresRefund_onlyPayosHoldingInARefundStatus(OrderStatus status) {
        boolean refundStatus = status == CONFIRMED_SYSTEM || status == PROCESSING || status == DELIVERING;
        assertThat(OrderStateMachine.requiresRefund(status, PaymentMethod.PAYOS, PaymentStatus.HOLDING))
                .isEqualTo(refundStatus);
        // never for COD, never unless the money is actually HOLDING in escrow
        assertThat(OrderStateMachine.requiresRefund(status, PaymentMethod.COD, PaymentStatus.HOLDING)).isFalse();
        assertThat(OrderStateMachine.requiresRefund(status, PaymentMethod.PAYOS, PaymentStatus.SUCCESS)).isFalse();
    }

    @Test
    void confirmedShop_isDeliberatelyNotARefundStatus() {
        assertThat(OrderStateMachine.requiresRefund(CONFIRMED_SHOP, PaymentMethod.PAYOS, PaymentStatus.HOLDING)).isFalse();
    }

    @Test
    void payoutAndRelease_areSeparateSteps() {
        assertThat(OrderStateMachine.canReleaseFunds(COMPLETED, PaymentStatus.HOLDING)).isTrue();
        assertThat(OrderStateMachine.canReleaseFunds(COMPLETED, PaymentStatus.RELEASED)).isFalse();
        assertThat(OrderStateMachine.canReleaseFunds(DELIVERING, PaymentStatus.HOLDING)).isFalse();

        assertThat(OrderStateMachine.shouldTriggerPayout(COMPLETED, PaymentStatus.RELEASED, false)).isTrue();
        assertThat(OrderStateMachine.shouldTriggerPayout(COMPLETED, PaymentStatus.HOLDING, false)).isFalse();
        assertThat(OrderStateMachine.shouldTriggerPayout(COMPLETED, PaymentStatus.RELEASED, true)).isFalse();
        assertThat(OrderStateMachine.shouldTriggerPayout(DELIVERING, PaymentStatus.RELEASED, false)).isFalse();
    }
}
