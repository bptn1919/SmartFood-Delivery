package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Django {@code CODSettlementResponse} ({@code transactions} defaults to [], never filled). */
public record CodSettlementResponse(boolean success, Long chefId, String chefEmail, BigDecimal settledAmount,
                                    int orderCount, String settledAt, String message, String createdAt,
                                    List<Map<String, Object>> transactions, String error) {
}
