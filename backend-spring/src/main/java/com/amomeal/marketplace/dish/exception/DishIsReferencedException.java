package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishIsReferenced. */
public class DishIsReferencedException extends ApiException {

    public DishIsReferencedException() {
        super(HttpStatus.FORBIDDEN, "DISH_IS_REFERENCED", "Dish is referenced");
    }
}
