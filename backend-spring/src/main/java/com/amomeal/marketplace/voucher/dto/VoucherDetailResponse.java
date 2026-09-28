package com.amomeal.marketplace.voucher.dto;

import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/voucher/schemas/response.py::VoucherDetailSchema. Note this schema
 * (unlike {@link VoucherListResponse}) does NOT expose {@code discount_type} — that's Django's
 * actual field list, not an omission introduced here.
 */
public record VoucherDetailResponse(
        UUID uid,
        String code,
        String name,
        String description,
        VoucherType voucherType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        Instant startDate,
        Instant endDate,
        Integer usageLimit,
        long usageCount,
        int usageLimitPerUser,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    /** {@code usageCount} mirrors {@code resolve_usage_count} — RESERVED+USED count for this voucher. */
    public static VoucherDetailResponse from(Voucher v, long usageCount) {
        return new VoucherDetailResponse(
                v.getUid(), v.getCode(), v.getName(), v.getDescription(), v.getVoucherType(),
                v.getDiscountValue(), v.getMaxDiscountAmount(), v.getMinOrderAmount(),
                v.getStartDate(), v.getEndDate(), v.getUsageLimit(), usageCount,
                v.getUsageLimitPerUser(), v.isActive(), v.getCreatedAt(), v.getUpdatedAt());
    }
}
