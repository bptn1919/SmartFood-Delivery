package com.amomeal.marketplace.payment.dto;

import jakarta.validation.constraints.NotNull;

/** Django {@code PaymentOtpVerifyRequest} / {@code WithdrawConfirmRequest} (identical schemas). */
public record PaymentOtpVerifyRequest(@NotNull String resetSessionToken, @NotNull String otp) {
}
