package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/requests.py::IngredientAliasCreateSchema. */
public record IngredientAliasCreateRequest(
        @JsonProperty("ingredient_uid") @NotNull UUID ingredientUid,
        @NotBlank String alias
) {
}
