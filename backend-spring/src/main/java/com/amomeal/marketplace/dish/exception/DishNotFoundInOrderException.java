package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishNotFoundInOrderException. */
public class DishNotFoundInOrderException extends ApiException {

    public DishNotFoundInOrderException() {
        super(HttpStatus.NOT_FOUND, "DISH_NOT_FOUND_IN_ORDER", "Dish not found in order");
    }
}
