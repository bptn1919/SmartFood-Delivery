package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/vouchers.py::VoucherNotOwnedException. Raised by
 * {@code VoucherService.updateVoucher}/{@code deleteVoucher} when the caller is neither the
 * voucher's owning chef nor "admin" — see those methods' javadoc for the exact ADMIN-group-OR
 * {@code is_staff} authorization quirk this guards.
 */
public class VoucherNotOwnedException extends ApiException {

    public VoucherNotOwnedException() {
        super(HttpStatus.FORBIDDEN, "VOUCHER_NOT_OWNED", "Bạn không có quyền thao tác voucher này");
    }
}
