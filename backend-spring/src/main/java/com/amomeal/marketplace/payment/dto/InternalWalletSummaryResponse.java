package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Django {@code InternalWalletSummaryResponse} ({@code wallet_uid} is always null: InternalWallet has no uid). */
public record InternalWalletSummaryResponse(UUID walletUid, Long userId, BigDecimal balance, BigDecimal pendingBalance,
                                            BigDecimal totalBalance, String currency,
                                            List<WalletTransactionResponse> recentTransactions) {
}
