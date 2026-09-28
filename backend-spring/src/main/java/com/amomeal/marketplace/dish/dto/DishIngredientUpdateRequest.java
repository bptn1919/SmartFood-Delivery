package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/requests.py::DishIngredientSchema
 * (PATCH /api/dishingredients/{uid}). Unlike
 * {@link DishIngredientCreateRequest} this schema declares all 19 nutrition
 * fields plus {@code custom_name}/{@code source}.
 */
public record DishIngredientUpdateRequest(
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("custom_name") String customName,
        IngredientSource source,
        double weight,
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
            case "cholesterol" -> cholesterol;
            case "retinol" -> retinol;
            case "caroten" -> caroten;
            case "vitamin_b_1" -> vitaminB1;
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
