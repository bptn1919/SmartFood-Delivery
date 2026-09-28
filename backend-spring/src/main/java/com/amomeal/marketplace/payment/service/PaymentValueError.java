package com.amomeal.marketplace.payment.service;

/**
 * Python {@code ValueError} as raised by ../../backend/payment/services.py (e.g.
 * "Checkout ... not found", "Invalid payment status transition: A -&gt; B",
 * "Payment ... not confirmed"). Not an {@code ApiException}: Django's generic handler
 * renders an uncaught ValueError as 500 CONTACT_ADMIN_FOR_SUPPORT, which is what the global
 * catch-all does with this. Its own type exists because a few callers catch ValueError
 * specifically ({@code PaymentController.create_payment}, {@code payos_return}).
 */
public class PaymentValueError extends RuntimeException {

    public PaymentValueError(String message) {
        super(message);
    }
}
