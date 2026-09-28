package com.amomeal.marketplace.voucher.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mirrors ../../backend/voucher/schemas/request.py::UpdateVoucherSchema. Notably has no
 * {@code code} and no {@code discount_type}/{@code voucher_type} field — see
 * {@code VoucherService.updateVoucher}'s javadoc for what that makes structurally
 * unreachable in Django's own {@code update_voucher} service method.
 *
 * <p>Every field is a partial-update field: absent (null) means "leave unchanged" — matching
 * Django's own {@code if value is not None: setattr(...)}, which also means there is no way
 * to explicitly clear a nullable field like {@code max_discount_amount} back to null via this
 * endpoint, in Django or here.
 */
public record UpdateVoucherRequest(
        String name,
        String description,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        Instant startDate,
        Instant endDate,
        Integer usageLimit,
        Integer usageLimitPerUser,
        Boolean isActive
) {
}
