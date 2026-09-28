package com.amomeal.marketplace.voucher.entity;

/**
 * Mirrors ../../backend/utils/enums.py::VoucherTypeEnum.
 *
 * <p>{@code SHOP_VOUCHER} is chef-created (the only kind {@code VoucherController.create_voucher}
 * can actually produce — {@code VoucherORM.create_voucher} hardcodes {@code voucher_type=SHOP_VOUCHER}
 * regardless of what a caller might otherwise want). {@code PLATFORM_SUBTOTAL}/{@code PLATFORM_SHIPPING}
 * are admin-created (via {@code admin/services.py::AdminService.create_voucher}, not ported here —
 * {@code admin} is a later module per CLAUDE.md §7 — but the enum values and the reservation logic
 * that consumes them are).
 */
public enum VoucherType {
    SHOP_VOUCHER,
    PLATFORM_SUBTOTAL,
    PLATFORM_SHIPPING
}
