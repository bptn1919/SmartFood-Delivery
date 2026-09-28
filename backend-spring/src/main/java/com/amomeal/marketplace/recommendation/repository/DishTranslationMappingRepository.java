package com.amomeal.marketplace.recommendation.repository;

import com.amomeal.marketplace.recommendation.entity.DishTranslationMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DishTranslationMappingRepository extends JpaRepository<DishTranslationMapping, UUID> {

    /** Django: {@code filter(active=True, normalized_vietnamese_name=normalized).first()} (pk order). */
    Optional<DishTranslationMapping> findFirstByActiveTrueAndNormalizedVietnameseNameOrderByUidAsc(String normalized);

    /** Django: {@code filter(active=True, normalized_vietnamese_name__icontains=normalized).first()}. */
    @Query(value = "SELECT * FROM dish_translation_mapping WHERE active = TRUE "
            + "AND normalized_vietnamese_name ILIKE ('%' || :needle || '%') ORDER BY uid LIMIT 1",
            nativeQuery = true)
    Optional<DishTranslationMapping> findFirstActiveContaining(@Param("needle") String escapedNeedle);
}
