package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Django {@code WalletTransactionResponse}. */
public record WalletTransactionResponse(UUID uid, String transactionType, String status, BigDecimal amount,
                                        String referenceId, String description, BigDecimal balanceBefore,
                                        BigDecimal balanceAfter, String payoutId, UUID orderUid, Instant createdAt,
                                        Instant processedAt) {
}
