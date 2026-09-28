package com.amomeal.marketplace.ingredient.entity;

/**
 * Mirrors ../../backend/utils/enums.py::IngredientImportStatusEnum. Despite the
 * name (inherited from Django), this is the moderation status of an
 * {@link IngredientSuggestion}, not of the Excel import feature.
 */
public enum IngredientImportStatus {
    PENDING,
    APPROVED,
    REJECTED
}
