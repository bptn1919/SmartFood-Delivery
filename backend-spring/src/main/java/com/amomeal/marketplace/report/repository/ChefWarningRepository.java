package com.amomeal.marketplace.report.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.report.entity.ChefWarning;
import com.amomeal.marketplace.report.entity.WarningType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ChefWarningRepository extends JpaRepository<ChefWarning, Long> {

    /** Django: the "no duplicate warning within 24h" check (null dish = IS NULL). */
    boolean existsByChefIdAndWarnedDishAndWarningTypeAndCreatedAtGreaterThanEqual(
            Long chefId, Dish warnedDish, WarningType type, Instant cutoff);

    boolean existsByChefIdAndWarningTypeAndCreatedAtGreaterThanEqual(Long chefId, WarningType type, Instant cutoff);
}
