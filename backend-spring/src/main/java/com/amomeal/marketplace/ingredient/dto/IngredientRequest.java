package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Mirrors ../../backend/ingredient/schemas/requests.py::IngredientSchema
 * (ModelSchema over Ingredient excluding attachment/created_at/deleted/
 * name_no_accent/owner/uid/updated_at/updater). Used for both create and
 * update — Django's update_ingredient sets every field from the payload too.
 */
public record IngredientRequest(
        @NotBlank String name,
        @NotNull IngredientCategory category,
        Double weight,
        Double energy,
        Double protein,
        Double lipid,
        Double carbohydrate,
        Double fiber,
        Double natri,
        Double kali,
        Double cholesterol,
        Double retinol,
        Double caroten,
        @JsonProperty("vitamin_b_1") Double vitaminB1,
        @JsonProperty("vitamin_b_2") Double vitaminB2,
        @JsonProperty("vitamin_pp") Double vitaminPp,
        @JsonProperty("vitamin_c") Double vitaminC,
        Double calcium,
        Double phosphorus,
        Double fe,
        Double mg,
        Double zn,
        IngredientSource source
) {
}
