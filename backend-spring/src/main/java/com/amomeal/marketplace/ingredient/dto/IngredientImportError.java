package com.amomeal.marketplace.ingredient.dto;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientImportError. */
public record IngredientImportError(int row, String message) {
}
