package com.amomeal.marketplace.payment.dto;

/** Django {@code CancelPaymentResponse}. */
public record CancelPaymentResponse(boolean success, String status, String cancelledAt, String cancellationReason,
                                    String error) {
}
