package com.amomeal.marketplace.payment.dto;

import java.math.BigDecimal;

/** Django {@code ChefBalanceSummaryResponse} + its two nested schemas. */
public record ChefBalanceSummaryResponse(Long chefId, String chefEmail, CodBalance codBalance, PayosBalance payosBalance,
                                         BigDecimal totalAvailablePayout, BigDecimal totalSettled, String currency) {

    /** Django {@code ChefCODBalanceResponse}. */
    public record CodBalance(BigDecimal unsettledBalance, int unsettledOrders, String note) {
    }

    /** Django {@code ChefPayOSBalanceResponse}. */
    public record PayosBalance(BigDecimal pendingPayout, String note) {
    }
}
