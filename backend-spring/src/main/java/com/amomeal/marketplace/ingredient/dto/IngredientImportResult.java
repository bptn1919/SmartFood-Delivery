package com.amomeal.marketplace.ingredient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientImportResult. */
public record IngredientImportResult(
        @JsonProperty("total_rows") int totalRows,
        @JsonProperty("created_count") int createdCount,
        @JsonProperty("failed_count") int failedCount,
        List<IngredientImportError> errors
) {
}
