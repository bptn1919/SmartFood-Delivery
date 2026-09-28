package com.amomeal.marketplace.payment.dto;

/** Django {@code PaymentOtpSessionResponse}. */
public record PaymentOtpSessionResponse(String resetSessionToken, String message) {
}
