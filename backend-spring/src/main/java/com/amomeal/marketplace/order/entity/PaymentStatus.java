package com.amomeal.marketplace.order.entity;

/**
 * Mirrors ../../backend/utils/enums.py::PaymentStatus. Stored on
 * {@code order.payment_status} (Django keeps the authoritative copy on
 * {@code payment.PaymentTransactionState}; {@code Order} carries a
 * denormalized snapshot, synced in {@code cancel_order}).
 *
 * <p>{@code HOLDING} is the escrow state: money received from PayOS but not yet
 * moved into the chef's internal wallet; {@code RELEASED} is after
 * {@code complete_order_with_release} moves it.
 */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    HOLDING,
    RELEASED,
    REFUND_PENDING,
    REFUNDED,
    FAILED,
    CANCELLED
}
