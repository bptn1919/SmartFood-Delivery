package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientIsReferenced. */
public class IngredientIsReferencedException extends ApiException {
    public IngredientIsReferencedException() {
        super(HttpStatus.FORBIDDEN, "INGREDIENT_IS_REFERENCED", "Ingredient is referenced");
    }
}
