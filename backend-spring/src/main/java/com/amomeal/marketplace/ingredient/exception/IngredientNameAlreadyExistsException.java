package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientNameAlreadyExists. */
public class IngredientNameAlreadyExistsException extends ApiException {
    public IngredientNameAlreadyExistsException() {
        super(HttpStatus.BAD_REQUEST, "INGREDIENT_NAME_ALREADY_EXISTS", "Ingredient with the same name already exists");
    }
}
