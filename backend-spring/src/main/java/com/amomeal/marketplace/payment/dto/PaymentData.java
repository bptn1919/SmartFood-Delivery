package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Django {@code PaymentData}. */
public record PaymentData(UUID paymentUid, UUID checkoutUid, String paymentMethod, BigDecimal amount, String status,
                          String paymentUrl, String qrCode, String paymentLinkId, Long orderCode, String accountNumber,
                          String accountName, String transactionId, Map<String, Object> settlement) {
}
