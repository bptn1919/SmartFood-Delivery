package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/vouchers.py::VoucherMinOrderException.
 *
 * <p>PORT-NOTE: dead code in Django, and even the message text has a bug baked in that would
 * survive if it were ever raised: Django's {@code APIException.__init__} never calls
 * {@code .format()} on the class-level {@code message} attribute, so the literal (unformatted)
 * Python format-string {@code "Đơn hàng tối thiểu phải từ {min_amount:,.0f}đ"} — curly braces
 * and all — is exactly what {@code self.message} would read if this class were ever
 * instantiated. The REAL "below minimum order amount" path
 * ({@code VoucherService.validateVoucherForOrder}) builds its own correctly-interpolated
 * Vietnamese string and returns it as a plain validation-result message, never raising this
 * class. Declared here only for 1:1 fidelity with {@code exceptions/vouchers.py}'s class list;
 * the unformatted template string is kept verbatim rather than "fixed" into working
 * interpolation, since fixing it would mean inventing behavior Django never actually has.
 */
public class VoucherMinOrderException extends ApiException {

    private static final String RAW_TEMPLATE = "Đơn hàng tối thiểu phải từ {min_amount:,.0f}đ";

    public VoucherMinOrderException() {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_MIN_ORDER", RAW_TEMPLATE);
    }
}
