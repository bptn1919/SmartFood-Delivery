package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/vouchers.py::VoucherNotFoundException. */
public class VoucherNotFoundException extends ApiException {

    public VoucherNotFoundException() {
        super(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND", "Voucher không tồn tại");
    }

    /** Mirrors call sites like {@code raise VoucherNotFoundException(f"...")} in the
     * reservation-apply flows — Django's APIException(detail) sets only {@code detail}. */
    public VoucherNotFoundException(Object detail) {
        super(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND", "Voucher không tồn tại", detail);
    }
}
