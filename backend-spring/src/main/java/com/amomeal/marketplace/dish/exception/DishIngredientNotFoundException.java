package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishIngredientNotFoundException. */
public class DishIngredientNotFoundException extends ApiException {

    public DishIngredientNotFoundException() {
        super(HttpStatus.NOT_FOUND, "DISH_INGREDIENT_NOT_FOUND", "Dish ingredient not found");
    }
}
