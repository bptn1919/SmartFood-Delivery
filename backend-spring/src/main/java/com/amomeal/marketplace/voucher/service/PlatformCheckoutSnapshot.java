package com.amomeal.marketplace.voucher.service;

import java.math.BigDecimal;

/**
 * The amounts {@code VoucherService.applyPlatformVoucherReservation} needs from a checkout,
 * pre-computed by the caller — see that method's javadoc. Only {@code netSubtotal} is used for
 * a {@code PLATFORM_SUBTOTAL} voucher; only {@code deliveryFee}/{@code checkoutSubtotal} are
 * used for a {@code PLATFORM_SHIPPING} voucher (mirroring Django's
 * {@code apply_platform_voucher_reservation}, which branches on {@code voucher_type} to decide
 * which of {@code checkout}'s fields it actually reads).
 */
public record PlatformCheckoutSnapshot(BigDecimal netSubtotal, BigDecimal deliveryFee, BigDecimal checkoutSubtotal) {
}
