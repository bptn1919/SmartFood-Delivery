package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** Mirrors ../../backend/ingredient/schemas/requests.py::IngredientSuggestionRejectSchema. */
public record IngredientSuggestionRejectRequest(
        @JsonProperty("rejection_reason") @NotBlank String rejectionReason
) {
}
