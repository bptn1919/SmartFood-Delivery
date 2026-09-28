package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientIsNotPending. */
public class IngredientIsNotPendingException extends ApiException {
    public IngredientIsNotPendingException() {
        super(HttpStatus.BAD_REQUEST, "INGREDIENT_IS_NOT_PENDING", "Ingredient suggestion is not in PENDING status");
    }
}
