package com.amomeal.marketplace.voucher.service;

import java.math.BigDecimal;

/** Mirrors the {@code Tuple[bool, str, Decimal]} returned by Django's
 * {@code VoucherService.validate_voucher_for_order}. */
public record VoucherValidationResult(boolean valid, String message, BigDecimal discountAmount) {
}
