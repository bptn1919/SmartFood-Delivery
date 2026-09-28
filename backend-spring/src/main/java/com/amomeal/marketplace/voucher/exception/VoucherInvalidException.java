package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/vouchers.py::VoucherInvalidException. Every real call site
 * in Django raises this WITH a dynamic reason string (e.g. {@code VoucherInvalidException(
 * "Voucher đã hết lượt sử dụng")}) — but that string becomes {@code detail} (the response's
 * {@code data} field), NOT {@code message}, per Django's {@code APIException.__init__(self,
 * detail)}: {@code message} always stays the static class attribute "Voucher không hợp lệ".
 * Ported faithfully via the {@code detail}-carrying constructor.
 */
public class VoucherInvalidException extends ApiException {

    public VoucherInvalidException() {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_INVALID", "Voucher không hợp lệ");
    }

    public VoucherInvalidException(Object detail) {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_INVALID", "Voucher không hợp lệ", detail);
    }
}
