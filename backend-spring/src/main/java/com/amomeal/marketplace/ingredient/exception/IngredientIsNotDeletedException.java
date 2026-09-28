package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientIsNotDeleted. */
public class IngredientIsNotDeletedException extends ApiException {
    public IngredientIsNotDeletedException() {
        super(HttpStatus.BAD_REQUEST, "INGREDIENT_NOT_DELETED", "Ingredient is not deleted");
    }
}
