package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientAliasResponse. */
public record IngredientAliasResponse(
        UUID uid,
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("ingredient_name") String ingredientName,
        String alias
) {
    public static IngredientAliasResponse from(IngredientAlias a) {
        return new IngredientAliasResponse(a.getUid(), a.getIngredient().getUid(), a.getIngredient().getName(), a.getAlias());
    }
}
