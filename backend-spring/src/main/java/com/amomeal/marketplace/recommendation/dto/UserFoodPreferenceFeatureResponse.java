package com.amomeal.marketplace.recommendation.dto;

import com.amomeal.marketplace.recommendation.entity.UserFoodPreferenceFeature;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors schemas/responses.py::UserFoodPreferenceFeatureResponse — a ninja ModelSchema of every
 * field except created_at/updated_at. The FK is serialized under the key {@code "user"} (the
 * ModelSchema field name; verified by dumping the schema in backend/venv).
 */
public record UserFoodPreferenceFeatureResponse(
        UUID uid,
        long user,
        List<Object> allergicIngredientIds,
        String dietMode,
        String dietLevel,
        String allergyMode,
        List<Object> favoriteIngredientIds,
        List<Object> favoriteDishIds,
        Double[] embedding
) {
    public static UserFoodPreferenceFeatureResponse of(UserFoodPreferenceFeature f) {
        return new UserFoodPreferenceFeatureResponse(f.getUid(), f.getUserId(), f.getAllergicIngredientIds(),
                f.getDietMode().name(), f.getDietLevel().name(), f.getAllergyMode().name(),
                f.getFavoriteIngredientIds(), f.getFavoriteDishIds(), f.getEmbedding());
    }
}
