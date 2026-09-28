package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/vouchers.py::VoucherExpiredException.
 *
 * <p>PORT-NOTE: dead code in Django — grepped every caller of {@code VoucherExpiredException}
 * across the whole backend tree; there are none. The real "voucher expired" path
 * ({@code Voucher.is_valid()}, both inline in {@code validate_voucher_for_order} and inside the
 * reservation-apply flows) raises {@link VoucherInvalidException} with the message
 * "Voucher đã hết hạn" instead. Declared here only for 1:1 fidelity with
 * {@code exceptions/vouchers.py}'s (de-duplicated, see PROGRESS.md) class list.
 */
public class VoucherExpiredException extends ApiException {

    public VoucherExpiredException() {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_EXPIRED", "Voucher đã hết hạn");
    }
}
