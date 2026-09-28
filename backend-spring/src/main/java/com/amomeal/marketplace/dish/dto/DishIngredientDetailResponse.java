package com.amomeal.marketplace.dish.dto;

import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Mirrors ../../backend/dish/schemas/responses.py::DishIngredientResponse —
 * GET /api/dishingredients/{uid} (FE-admin DISH_INGREDIENT_DETAIL).
 */
public record DishIngredientDetailResponse(
        @JsonProperty("dish_uid") UUID dishUid,
        @JsonProperty("ingredient_uid") UUID ingredientUid,
        @JsonProperty("ingredient_name") String ingredientName,
        @JsonProperty("custom_name") String customName,
        IngredientSource source,
        @JsonProperty("suggestion_uid") UUID suggestionUid,
        @JsonProperty("approval_status") IngredientImportStatus approvalStatus,
        @JsonProperty("created_by_id") Long createdById,
        @JsonProperty("updated_by_id") Long updatedById,
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
        Double zn
) {

    public static DishIngredientDetailResponse from(DishIngredient di) {
        return new DishIngredientDetailResponse(
                di.getDish().getUid(),
                di.getIngredient() == null ? null : di.getIngredient().getUid(),
                di.getIngredient() == null ? null : di.getIngredient().getName(),
                di.getCustomName(),
                di.getSource(),
                di.getSuggestion() == null ? null : di.getSuggestion().getUid(),
                di.getApprovalStatus(),
                di.getCreatedBy() == null ? null : di.getCreatedBy().getId(),
                di.getUpdatedBy() == null ? null : di.getUpdatedBy().getId(),
                di.getWeight(),
                di.getEnergy(),
                di.getProtein(),
                di.getLipid(),
                di.getCarbohydrate(),
                di.getFiber(),
                di.getNatri(),
                di.getKali(),
                di.getCholesterol(),
                di.getRetinol(),
                di.getCaroten(),
                di.getVitaminB1(),
                di.getVitaminB2(),
                di.getVitaminPp(),
                di.getVitaminC(),
                di.getCalcium(),
                di.getPhosphorus(),
                di.getFe(),
                di.getMg(),
                di.getZn());
    }
}
