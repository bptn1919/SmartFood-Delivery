package com.amomeal.marketplace.voucher.dto;

import java.math.BigDecimal;

/** Mirrors ../../backend/voucher/schemas/response.py::ValidateVoucherResponseSchema. */
public record ValidateVoucherResponse(
        boolean isValid,
        String message,
        BigDecimal discountAmount,
        BigDecimal finalAmount
) {
}
