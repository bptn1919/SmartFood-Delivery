package com.amomeal.marketplace.voucher.dto;

import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherDiscountType;
import com.amomeal.marketplace.voucher.entity.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/voucher/schemas/response.py::VoucherListSchema — a Django
 * {@code ModelSchema} that auto-includes every {@code Voucher} model field except
 * {@code created_at}/{@code chef}/{@code updated_at}/{@code description}. Field set ported
 * 1:1 (includes {@code discount_type}, unlike {@link VoucherDetailResponse}).
 */
public record VoucherListResponse(
        UUID uid,
        String code,
        String name,
        VoucherType voucherType,
        VoucherDiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        Instant startDate,
        Instant endDate,
        Integer usageLimit,
        int usageLimitPerUser,
        boolean isActive
) {
    public static VoucherListResponse from(Voucher v) {
        return new VoucherListResponse(
                v.getUid(), v.getCode(), v.getName(), v.getVoucherType(), v.getDiscountType(),
                v.getDiscountValue(), v.getMaxDiscountAmount(), v.getMinOrderAmount(),
                v.getStartDate(), v.getEndDate(), v.getUsageLimit(), v.getUsageLimitPerUser(),
                v.isActive());
    }
}
