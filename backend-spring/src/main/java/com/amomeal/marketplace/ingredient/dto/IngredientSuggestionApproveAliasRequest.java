package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/requests.py::IngredientSuggestionApproveAliasSchema. */
public record IngredientSuggestionApproveAliasRequest(
        @JsonProperty("ingredient_uid") @NotNull UUID ingredientUid,
        @JsonProperty("resolution_note") String resolutionNote
) {
}
