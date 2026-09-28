package com.amomeal.marketplace.order.dto;

import com.amomeal.marketplace.voucher.entity.VoucherType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Mirrors ../../backend/order/schemas/requests.py::ApplyPlatformVoucherSchema.
 *
 * <p>Django types {@code voucher_type} as a bare {@code str} and lets
 * {@code VoucherService.apply_platform_voucher_reservation} reject anything that
 * is not PLATFORM_SUBTOTAL/PLATFORM_SHIPPING. Typed as the real enum here, which
 * makes a garbage value a 401 VALIDATION_ERROR instead of a 400
 * VOUCHER_INVALID — the only place this port narrows the contract, and only for
 * strings that are not voucher types at all. A valid-but-wrong type
 * (SHOP_VOUCHER) still reaches the service and gets Django's exact error.
 */
public record ApplyPlatformVoucherRequest(
        @NotBlank String voucherCode,
        @NotNull VoucherType voucherType) {
}
