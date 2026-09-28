package com.amomeal.marketplace.cart.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/carts.py::InvalidDeliveryDateException —
 * raised by {@code CartService.addItem} when {@code delivery_date} is in the
 * past ("Không thể đặt món cho ngày trong quá khứ").
 */
public class InvalidDeliveryDateException extends ApiException {

    public InvalidDeliveryDateException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_DATE", "Không thể đặt món cho ngày trong quá khứ");
    }
}
