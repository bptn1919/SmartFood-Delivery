package com.amomeal.marketplace.cart.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/carts.py::CartItemNotFoundException.
 *
 * <p>Not raised anywhere in {@code cart}'s own service today — Django's only
 * call site is {@code order/services/__init__.py::OrderService.checkout}
 * ("no selected cart items" at checkout time), and {@code order} is not
 * ported yet (CLAUDE.md §7 port order). Declared here anyway, per CLAUDE.md
 * §4 ("one class per Django exception in {@code exceptions/<app>.py}",
 * regardless of which app raises it) and so the future {@code order} port can
 * reuse it without redeclaring.
 */
public class CartItemNotFoundException extends ApiException {

    public CartItemNotFoundException() {
        super(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "Cart item not found");
    }
}
