package com.amomeal.marketplace.payment.dto;

/** Django {@code PaymentInfoResponse} ({@code amount_paid} defaults to 0, everything else to null). */
public record PaymentInfoResponse(boolean success, String paymentLinkId, Long orderCode, Long amount, Long amountPaid,
                                  Long amountRemaining, String status) {
}
