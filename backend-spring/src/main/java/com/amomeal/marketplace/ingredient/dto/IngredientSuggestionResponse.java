package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientSuggestionResponse. */
public record IngredientSuggestionResponse(
        UUID uid,
        String name,
        IngredientCategory category,
        IngredientImportStatus status,
        @JsonProperty("created_by_id") Long createdById,
        @JsonProperty("verified_by_id") Long verifiedById,
        @JsonProperty("verified_at") String verifiedAt,
        @JsonProperty("resolution_note") String resolutionNote
) {
    public static IngredientSuggestionResponse from(IngredientSuggestion s) {
        return new IngredientSuggestionResponse(
                s.getUid(),
                s.getSuggestedName(),
                s.getSuggestedCategory(),
                s.getStatus(),
                s.getCreatedBy() == null ? null : s.getCreatedBy().getId(),
                s.getVerifiedBy() == null ? null : s.getVerifiedBy().getId(),
                s.getVerifiedAt() == null ? null : s.getVerifiedAt().toString(),
                s.getResolutionNote());
    }
}
