package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::UserIngredientPreferenceResponse. */
public record UserIngredientPreferenceResponse(
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("ingredient_name") String ingredientName,
        IngredientCategory category
) {
    public static UserIngredientPreferenceResponse from(Ingredient i) {
        return new UserIngredientPreferenceResponse(i.getUid(), i.getName(), i.getCategory());
    }
}
