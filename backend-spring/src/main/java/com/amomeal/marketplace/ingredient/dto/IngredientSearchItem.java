package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientSearchItem. */
public record IngredientSearchItem(
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("suggestion_uid") UUID suggestionUid,
        String name,
        IngredientCategory category,
        @JsonProperty("matched_alias") String matchedAlias,
        double score,
        String source,
        @JsonProperty("approval_status") String approvalStatus,
        @JsonProperty("is_suggestion") boolean isSuggestion
) {
}
