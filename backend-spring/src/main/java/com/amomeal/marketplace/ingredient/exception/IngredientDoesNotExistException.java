package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientDoesNotExist. */
public class IngredientDoesNotExistException extends ApiException {
    public IngredientDoesNotExistException() {
        super(HttpStatus.NOT_FOUND, "INGREDIENT_NOT_FOUND", "Ingredient not found");
    }
}
