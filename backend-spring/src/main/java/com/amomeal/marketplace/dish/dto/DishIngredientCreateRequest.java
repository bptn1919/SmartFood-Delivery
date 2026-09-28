package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/requests.py::DishIngredientCreateSchema
 * (POST /api/dishes/{uid}/ingredients and .../ingredients/preview).
 *
 * <p>Note which nutrition fields Django's schema deliberately omits:
 * {@code lipid} and {@code carbohydrate} ARE present, but
 * {@code cholesterol}/{@code retinol}/{@code caroten}/{@code vitamin_b_1} are
 * NOT — so those can never be overridden on this endpoint and always take the
 * USDA-scaled value. Preserved exactly (see {@link NutritionPayload}).
 */
public record DishIngredientCreateRequest(
        @JsonProperty("ingredient_uid") UUID ingredientUid,
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
        Double zn
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
            // cholesterol / retinol / caroten / vitamin_b_1 are not on this schema.
            default -> null;
        };
    }
}
