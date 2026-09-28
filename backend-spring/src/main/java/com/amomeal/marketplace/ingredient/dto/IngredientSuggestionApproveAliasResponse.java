package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientSuggestionApproveAliasResponse. */
public record IngredientSuggestionApproveAliasResponse(
        @JsonProperty("suggestion_uid") UUID suggestionUid,
        String name,
        IngredientCategory category,
        IngredientImportStatus status,
        @JsonProperty("resolved_ingredient_uid") UUID resolvedIngredientUid,
        @JsonProperty("resolved_alias_uid") UUID resolvedAliasUid,
        @JsonProperty("created_by_id") Long createdById,
        @JsonProperty("verified_by_id") Long verifiedById,
        @JsonProperty("verified_at") String verifiedAt,
        @JsonProperty("resolution_note") String resolutionNote
) {
}
