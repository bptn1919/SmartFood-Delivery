package com.amomeal.marketplace.voucher.entity;

/**
 * Mirrors ../../backend/utils/enums.py::VoucherReservationStatus.
 *
 * <p>Django's {@code voucher} module only ever writes {@code RESERVED} (on apply) and
 * {@code EXPIRED} (the lazy {@code expire_old_reservations} sweep, called inline at the
 * start of every apply attempt — there is no scheduled/Celery job for voucher expiry,
 * see {@code VoucherService}'s class javadoc). The {@code USED} and {@code CANCELLED}
 * transitions are written directly by {@code order/services/__init__.py} via raw ORM
 * {@code .update(status=...)} calls (checkout confirm -> USED, order cancel -> CANCELLED)
 * — {@code voucher}'s own service never performs those transitions itself, in Django or
 * here. See {@code VoucherService}'s "forward seam for order" javadoc section.
 */
public enum VoucherReservationStatus {
    RESERVED,
    USED,
    CANCELLED,
    EXPIRED
}
