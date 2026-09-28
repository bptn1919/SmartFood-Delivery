package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/requests.py::UserIngredientPreferenceSchema. */
public record UserIngredientPreferenceRequest(
        @JsonProperty("ingredient_uid") @NotNull UUID ingredientUid
) {
}
