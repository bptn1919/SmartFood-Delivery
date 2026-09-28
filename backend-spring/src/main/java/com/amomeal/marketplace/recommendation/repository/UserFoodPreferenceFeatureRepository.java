package com.amomeal.marketplace.recommendation.repository;

import com.amomeal.marketplace.recommendation.entity.UserFoodPreferenceFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Django: {@code RecommendationORM.get_user_food_preference_features} + update_or_create. */
public interface UserFoodPreferenceFeatureRepository extends JpaRepository<UserFoodPreferenceFeature, UUID> {

    Optional<UserFoodPreferenceFeature> findByUserId(Long userId);
}
