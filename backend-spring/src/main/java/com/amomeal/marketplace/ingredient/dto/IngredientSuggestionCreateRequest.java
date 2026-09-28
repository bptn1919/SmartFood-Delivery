package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * PORT-NOTE: no Django/ninja equivalent — see
 * {@link com.amomeal.marketplace.ingredient.service.IngredientSuggestionService#createSuggestion}
 * javadoc and PROGRESS.md. In Django, suggestions are created internally by
 * {@code dish}'s custom-ingredient flow
 * (../../backend/ingredient/services/__init__.py::IngredientSuggestionService.create_suggestion,
 * called with a {@code DishIngredientSuggestionSchema} payload whose relevant
 * fields are {@code custom_name}/{@code category}), never through a public
 * ingredient-controller endpoint. Exposed directly here (POST
 * /api/ingredients/suggestions, CHEF-only) as a stand-in so the moderation
 * queue is usable/testable before `dish` is ported.
 */
public record IngredientSuggestionCreateRequest(
        @JsonProperty("custom_name") @NotBlank String customName,
        @NotNull IngredientCategory category
) {
}
