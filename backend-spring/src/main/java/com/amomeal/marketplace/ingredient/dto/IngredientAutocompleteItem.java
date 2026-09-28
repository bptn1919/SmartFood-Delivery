package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;

import java.util.UUID;

/** Mirrors ../../backend/ingredient/schemas/responses.py::IngredientAutocompleteItem. */
public record IngredientAutocompleteItem(UUID uid, String name, IngredientCategory category) {
}
