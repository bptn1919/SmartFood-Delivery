package com.amomeal.marketplace.voucher.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/vouchers.py::VoucherUsageLimitException.
 *
 * <p>PORT-NOTE: {@code exceptions/vouchers.py} literally defines its whole exception class
 * list TWICE back to back (a copy-paste artifact, not two meaningfully different blocks —
 * every class in the first block reappears byte-identical in the second). This class exists
 * ONLY in the second copy, with no first-block counterpart, but that placement doesn't make it
 * live: grepped every raise site across the whole Django backend tree and there are none. Every
 * real "voucher usage limit exceeded" path (both inside {@code validate_voucher_for_order} and
 * inside the two reservation-apply flows, {@code apply_platform_voucher_reservation}/
 * {@code apply_shop_voucher_reservation}) raises {@link VoucherInvalidException} with the
 * message "Voucher đã hết lượt sử dụng" instead — this class is dead code regardless of which
 * copy of the file Python's module loader keeps (the second, since later class statements in a
 * module simply rebind the name). Declared here only for 1:1 fidelity with the de-duplicated
 * class list.
 */
public class VoucherUsageLimitException extends ApiException {

    public VoucherUsageLimitException() {
        super(HttpStatus.BAD_REQUEST, "VOUCHER_USAGE_LIMIT", "Voucher đã hết lượt sử dụng");
    }
}
