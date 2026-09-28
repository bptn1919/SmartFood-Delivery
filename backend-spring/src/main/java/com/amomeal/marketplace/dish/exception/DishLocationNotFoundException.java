package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishLocationNotFoundException. */
public class DishLocationNotFoundException extends ApiException {

    public DishLocationNotFoundException() {
        super(HttpStatus.NOT_FOUND, "DISH_LOCATION_NOT_FOUND", "Dish location not found");
    }
}
