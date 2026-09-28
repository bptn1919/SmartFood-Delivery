package com.amomeal.marketplace.payment.dto;

/** Django {@code CreatePaymentResponse} — {@code success=false} + message on failure, still HTTP 200. */
public record CreatePaymentResponse(boolean success, String message, PaymentData data) {
}
