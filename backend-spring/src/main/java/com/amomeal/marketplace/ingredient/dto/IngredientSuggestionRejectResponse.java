package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientSuggestionRejectResponse. */
public record IngredientSuggestionRejectResponse(
        @JsonProperty("suggestion_uid") UUID suggestionUid,
        String name,
        IngredientCategory category,
        IngredientImportStatus status,
        @JsonProperty("rejection_reason") String rejectionReason,
        @JsonProperty("created_by_id") Long createdById,
        @JsonProperty("verified_by_id") Long verifiedById,
        @JsonProperty("verified_at") String verifiedAt
) {
}
