package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Django {@code PaymentStatusResponse}. */
public record PaymentStatusResponse(UUID paymentUid, String transactionId, String status, BigDecimal amount,
                                    String paymentMethod, Instant createdAt, Instant paidAt,
                                    Map<String, Object> settlement) {
}
