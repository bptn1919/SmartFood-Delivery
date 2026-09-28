package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/vouchers.py::VoucherCodeAlreadyExistsException. */
public class VoucherCodeAlreadyExistsException extends ApiException {

    public VoucherCodeAlreadyExistsException() {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_CODE_ALREADY_EXISTS", "Mã voucher đã tồn tại");
    }
}
