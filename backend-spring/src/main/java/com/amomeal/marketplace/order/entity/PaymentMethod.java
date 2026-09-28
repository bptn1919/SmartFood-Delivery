package com.amomeal.marketplace.order.entity;

/**
 * Mirrors ../../backend/utils/enums.py::PaymentMethodEnum.
 *
 * <p>Owned by {@code order} in this port (not {@code payment}) because
 * {@code order.models.Checkout.payment_method} is the only column that stores
 * it and {@code payment} is not ported yet — the future {@code payment} module
 * reuses this enum rather than redeclaring it.
 */
public enum PaymentMethod {
    /** Cash on delivery — no external gateway, the sale is final at place-order time. */
    COD,
    /** PayOS online payment — stock stays merely RESERVED until the webhook confirms. */
    PAYOS
}
