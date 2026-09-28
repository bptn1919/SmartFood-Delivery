package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishLocationHasChildrenException. */
public class DishLocationHasChildrenException extends ApiException {

    public DishLocationHasChildrenException() {
        super(HttpStatus.BAD_REQUEST, "DISH_LOCATION_HAS_CHILDREN", "Cannot delete location that still has children");
    }
}
