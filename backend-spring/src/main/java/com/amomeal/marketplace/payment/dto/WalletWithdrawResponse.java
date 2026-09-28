package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;

/** Django {@code WalletWithdrawResponse}. */
public record WalletWithdrawResponse(boolean success, String message, BigDecimal amount, String status, String payoutId,
                                     String referenceId, String bankAccount, String error) {
}
