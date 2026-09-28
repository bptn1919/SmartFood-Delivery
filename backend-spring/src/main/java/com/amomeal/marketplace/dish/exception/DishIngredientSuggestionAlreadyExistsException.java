package com.amomeal.marketplace.dish.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors ../../backend/exceptions/dishes.py::DishIngredientSuggestionAlreadyExists. */
public class DishIngredientSuggestionAlreadyExistsException extends ApiException {

    public DishIngredientSuggestionAlreadyExistsException() {
        super(HttpStatus.BAD_REQUEST, "DISH_INGREDIENT_SUGGESTION_ALREADY_EXISTS", "You have already suggested an ingredient with the same name for this dish");
    }
}
