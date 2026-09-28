package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Post-port suspension enforcement (2026-09-25). A dish with {@code is_suspended=true}
 * (report's DISH_LOCK) can't be added to a cart or ordered. Django never enforced this.
 */
public class DishSuspendedException extends ApiException {

    public DishSuspendedException() {
        super(HttpStatus.BAD_REQUEST, "DISH_SUSPENDED", "This dish is currently suspended and cannot be ordered");
    }
}
