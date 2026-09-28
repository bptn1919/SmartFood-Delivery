package com.amomeal.marketplace.order.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/orders.py::OrderNotFoundException — the ONLY
 * class in that file (checked: 9 lines total). Every other failure mode in
 * {@code order/services/__init__.py} is either a bare Python {@code ValueError}
 * / ninja {@code HttpError}, or an exception owned by another app
 * ({@code exceptions/carts.py::CartItemNotFoundException},
 * {@code exceptions/profiles.py::CustomerAddressNotFoundException},
 * {@code exceptions/vouchers.py::VoucherInvalidException},
 * {@code exceptions/dishes.py::DishNotFoundInOrderException}) — all of which are
 * already ported in their own modules and are reused here rather than
 * redeclared.
 */
public class OrderNotFoundException extends ApiException {

    public OrderNotFoundException() {
        super(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }
}
