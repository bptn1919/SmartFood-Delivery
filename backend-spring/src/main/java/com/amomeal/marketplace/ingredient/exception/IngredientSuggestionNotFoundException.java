package com.amomeal.marketplace.ingredient.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/ingredient.py::IngredientSuggestionNotFound. */
public class IngredientSuggestionNotFoundException extends ApiException {
    public IngredientSuggestionNotFoundException() {
        super(HttpStatus.NOT_FOUND, "INGREDIENT_SUGGESTION_NOT_FOUND", "Ingredient suggestion not found");
    }
}
