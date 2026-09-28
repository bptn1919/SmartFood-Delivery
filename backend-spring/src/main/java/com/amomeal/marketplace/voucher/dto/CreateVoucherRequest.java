package com.amomeal.marketplace.voucher.dto;

import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/** Mirrors ../../backend/voucher/schemas/request.py::CreateVoucherSchema. */
public record CreateVoucherRequest(
        @NotBlank String code,
        @NotBlank String name,
        String description,
        @NotNull VoucherDiscountType discountType,
        @NotNull BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        @NotNull Instant startDate,
        @NotNull Instant endDate,
        Integer usageLimit,
        Integer usageLimitPerUser,
        Boolean isActive
) {
}
