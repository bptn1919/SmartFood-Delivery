package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/vouchers.py::VoucherChefMismatchException.
 *
 * <p>PORT-NOTE: dead code in Django — the real "voucher doesn't belong to this order's chef"
 * check, in {@code validate_voucher_for_order}, returns a plain validation-result tuple
 * ({@code False, "Voucher này không áp dụng cho chef của đơn hàng", Decimal("0")}) instead of
 * raising this class. Declared here only for 1:1 fidelity with {@code exceptions/vouchers.py}.
 */
public class VoucherChefMismatchException extends ApiException {

    public VoucherChefMismatchException() {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_CHEF_MISMATCH", "Voucher này không áp dụng cho chef của đơn hàng");
    }
}
