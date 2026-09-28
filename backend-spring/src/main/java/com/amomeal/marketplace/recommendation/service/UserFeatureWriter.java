package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.ingredient.entity.AllergicIngredient;
import com.amomeal.marketplace.ingredient.entity.FavouriteIngredient;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.FavouriteIngredientRepository;
import com.amomeal.marketplace.profile.entity.CustomerFavoriteDish;
import com.amomeal.marketplace.profile.entity.CustomerProfile;
import com.amomeal.marketplace.profile.repository.CustomerFavoriteDishRepository;
import com.amomeal.marketplace.profile.repository.CustomerProfileRepository;
import com.amomeal.marketplace.recommendation.entity.UserFoodPreferenceFeature;
import com.amomeal.marketplace.recommendation.repository.UserFoodPreferenceFeatureRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The DB half of Django's {@code RecommendationService.rebuild_user_feature}: re-derives the
 * {@link UserFoodPreferenceFeature} snapshot from the source-of-truth tables owned by
 * {@code profile}/{@code ingredient} (read through their own repositories, nothing redeclared) and
 * {@code update_or_create}s it. Only the groups named in {@code updateFields} are touched
 * ({@code null} = all), exactly like Django's {@code defaults} dict.
 */
@Component
@RequiredArgsConstructor
public class UserFeatureWriter {

    private final UserFoodPreferenceFeatureRepository featureRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final FavouriteIngredientRepository favouriteIngredientRepository;
    private final AllergicIngredientRepository allergicIngredientRepository;
    private final CustomerFavoriteDishRepository customerFavoriteDishRepository;
    private final EntityManager entityManager;

    private static boolean wants(List<String> updateFields, String group) {
        return updateFields == null || updateFields.contains(group);
    }

    @Transactional
    public UserFoodPreferenceFeature rebuild(long userId, List<String> updateFields) {
        UserFoodPreferenceFeature feature = featureRepository.findByUserId(userId)
                .orElseGet(() -> UserFoodPreferenceFeature.builder().userId(userId).build());
        CustomUser userRef = entityManager.getReference(CustomUser.class, userId);

        if (wants(updateFields, "diet")) {
            CustomerProfile profile = customerProfileRepository.findByUserId(userId).orElse(null);
            if (profile != null) {
                feature.setDietMode(profile.getDietMode());
                feature.setDietLevel(profile.getDietLevel());
                feature.setAllergyMode(profile.getAllergyMode());
            }
        }
        if (wants(updateFields, "fav_ingredient")) {
            List<Object> ids = new ArrayList<>();
            for (FavouriteIngredient f : favouriteIngredientRepository.findAllByUserAndDeletedFalse(userRef)) {
                ids.add(f.getIngredient().getUid().toString());
            }
            feature.setFavoriteIngredientIds(ids);
        }
        if (wants(updateFields, "allergy")) {
            List<Object> ids = new ArrayList<>();
            for (AllergicIngredient a : allergicIngredientRepository.findAllByUserAndDeletedFalse(userRef)) {
                ids.add(a.getIngredient().getUid().toString());
            }
            feature.setAllergicIngredientIds(ids);
        }
        if (wants(updateFields, "fav_dish")) {
            List<Object> ids = new ArrayList<>();
            for (CustomerFavoriteDish f : customerFavoriteDishRepository.findAllByUserAndDeletedFalse(userRef)) {
                ids.add(f.getDish().getUid().toString());
            }
            feature.setFavoriteDishIds(ids);
        }
        return featureRepository.save(feature);
    }
}
