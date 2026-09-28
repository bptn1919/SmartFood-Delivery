package com.amomeal.marketplace.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Django {@code WalletWithdrawRequest}. */
public record WalletWithdrawRequest(@NotNull BigDecimal amount) {
}
