package com.amomeal.marketplace.ingredient.dto;

import com.amomeal.marketplace.attachment.dto.AttachmentResponse;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Mirrors ../../backend/ingredient/schemas/responses.py::IngredientResponse
 * (excludes created_at/deleted/name_no_accent/owner/updated_at/updater).
 */
public record IngredientResponse(
        UUID uid,
        String name,
        IngredientCategory category,
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
        IngredientSource source,
        AttachmentResponse attachment
) {
    public static IngredientResponse from(Ingredient i) {
        return new IngredientResponse(
                i.getUid(), i.getName(), i.getCategory(), i.getWeight(), i.getEnergy(), i.getProtein(),
                i.getLipid(), i.getCarbohydrate(), i.getFiber(), i.getNatri(), i.getKali(), i.getCholesterol(),
                i.getRetinol(), i.getCaroten(), i.getVitaminB1(), i.getVitaminB2(), i.getVitaminPp(),
                i.getVitaminC(), i.getCalcium(), i.getPhosphorus(), i.getFe(), i.getMg(), i.getZn(),
                i.getSource(), i.getAttachment() == null ? null : AttachmentResponse.from(i.getAttachment()));
    }
}
