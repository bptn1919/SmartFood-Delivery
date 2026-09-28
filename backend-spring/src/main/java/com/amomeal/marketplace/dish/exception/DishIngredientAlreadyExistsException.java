package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishIngredientAlreadyExists. */
public class DishIngredientAlreadyExistsException extends ApiException {

    public DishIngredientAlreadyExistsException() {
        super(HttpStatus.BAD_REQUEST, "DISH_INGREDIENT_ALREADY_EXISTS", "Ingredient already exists in dish");
    }
}
