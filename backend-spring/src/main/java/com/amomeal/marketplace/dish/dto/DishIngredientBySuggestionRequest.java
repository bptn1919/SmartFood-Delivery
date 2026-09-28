package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Mirrors ../../backend/dish/schemas/requests.py::DishIngredientCreateBySuggestionSchema. */
public record DishIngredientBySuggestionRequest(
        @JsonProperty("suggestion_uid") @NotNull UUID suggestionUid,
        double weight
) {
}
