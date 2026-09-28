package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/requests.py::DishIngredientSuggestionSchema
 * (POST /api/dishes/{uid}/ingredients/suggestion[/preview]) — a chef proposing a
 * custom ingredient that is not in the USDA table yet.
 *
 * <p>Same field-omission quirk as {@link DishIngredientCreateRequest}:
 * {@code cholesterol}/{@code retinol}/{@code caroten}/{@code vitamin_b_1} are
 * not declared in Django and therefore can never be supplied here.
 */
public record DishIngredientSuggestionRequest(
        @JsonProperty("custom_name") @NotBlank String customName,
        @NotNull IngredientCategory category,
        @JsonProperty("attachment_uid") UUID attachmentUid,
        double weight,
        Double energy,
        Double protein,
        Double lipid,
        Double carbohydrate,
        Double fiber,
        Double natri,
        Double kali,
        @JsonProperty("vitamin_b_2") Double vitaminB2,
        @JsonProperty("vitamin_pp") Double vitaminPp,
        @JsonProperty("vitamin_c") Double vitaminC,
        Double calcium,
        Double phosphorus,
        Double fe,
        Double mg,
        Double zn,
        Integer limit
) implements NutritionPayload {

    @Override
    public Double nutrient(String field) {
        return switch (field) {
            case "energy" -> energy;
            case "protein" -> protein;
            case "lipid" -> lipid;
            case "carbohydrate" -> carbohydrate;
            case "fiber" -> fiber;
            case "natri" -> natri;
            case "kali" -> kali;
            case "vitamin_b_2" -> vitaminB2;
            case "vitamin_pp" -> vitaminPp;
            case "vitamin_c" -> vitaminC;
            case "calcium" -> calcium;
            case "phosphorus" -> phosphorus;
            case "fe" -> fe;
            case "mg" -> mg;
            case "zn" -> zn;
            default -> null;
        };
    }
}
