package com.amomeal.marketplace.cart.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mirrors ../../backend/exceptions/carts.py::CartNotFoundException.
 *
 * <p>PORT-NOTE: this is dead code in Django, same class of bug as
 * {@code ingredient}'s {@code restore_ingredient} / {@code menu}'s
 * {@code restore_menu} (see PROGRESS.md). {@code CartService.get_cart_by_user}
 * raises it only when {@code CartORM.get_cart_by_user} returns falsy, but that
 * method is {@code Cart.objects.get_or_create(owner=user)}, which always
 * returns a {@code Cart} — never {@code None}. Confirmed unreachable by the
 * Django coverage report (line marked "missed"). Ported faithfully (see
 * {@code CartService.getCartByUser}) and regression-tested to document the
 * branch can never fire via the real code path.
 */
public class CartNotFoundException extends ApiException {

    public CartNotFoundException() {
        super(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", "Cart not found");
    }
}
