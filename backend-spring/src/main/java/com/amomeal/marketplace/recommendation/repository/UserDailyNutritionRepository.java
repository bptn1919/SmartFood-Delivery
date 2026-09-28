package com.amomeal.marketplace.recommendation.repository;

import com.amomeal.marketplace.recommendation.entity.UserDailyNutrition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface UserDailyNutritionRepository extends JpaRepository<UserDailyNutrition, UUID> {

    Optional<UserDailyNutrition> findByUserIdAndDate(Long userId, LocalDate date);
}
