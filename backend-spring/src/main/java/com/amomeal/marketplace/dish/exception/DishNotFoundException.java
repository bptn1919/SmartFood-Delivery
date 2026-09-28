package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishNotFoundException. */
public class DishNotFoundException extends ApiException {

    public DishNotFoundException() {
        super(HttpStatus.NOT_FOUND, "DISH_NOT_FOUND", "Dish not found");
    }
}
