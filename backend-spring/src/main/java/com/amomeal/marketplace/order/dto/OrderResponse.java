package com.amomeal.marketplace.order.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/schemas/responses.py::OrderResponse — the
 * per-chef order shape nested inside a {@link CheckoutResponse}.
 *
 * <p>Note {@code chefName} here comes from Django's {@code get_full_name()},
 * while {@link OrderResponseWithInfo}'s comes from {@code username} (see
 * {@code OrderMapper}'s PORT-NOTE) — the two schemas genuinely disagree in
 * Django and that is preserved.
 */
public record OrderResponse(
        UUID uid,
        Long chefId,
        String chefName,
        BigDecimal subTotal,
        BigDecimal taxAndFees,
        BigDecimal deliveryFee,
        BigDecimal platformSubtotalDiscount,
        BigDecimal platformShippingDiscount,
        BigDecimal shopDiscount,
        BigDecimal totalDiscount,
        BigDecimal totalPrice,
        String voucherCode,
        List<OrderItemResponse> items) {
}
