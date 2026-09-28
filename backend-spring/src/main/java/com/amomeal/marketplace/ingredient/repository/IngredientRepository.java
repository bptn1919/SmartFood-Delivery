package com.amomeal.marketplace.ingredient.repository;

import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngredientRepository extends JpaRepository<Ingredient, UUID>, JpaSpecificationExecutor<Ingredient> {

    Optional<Ingredient> findByUidAndDeletedFalse(UUID uid);

    boolean existsByNameIgnoreCaseAndDeletedFalse(String name);

    List<Ingredient> findAllByDeletedFalse();

    List<Ingredient> findAllByUidInAndDeletedFalse(List<UUID> uids);

    List<Ingredient> findAllByNameNoAccentAndDeletedFalse(String nameNoAccent);

    /** Mirrors the "name_exact" stage of IngredientORM.find_suggestion_candidates. */
    @Query("SELECT i FROM Ingredient i WHERE i.deleted = false AND i.nameNoAccent = :name "
            + "AND (:category IS NULL OR i.category = :category)")
    List<Ingredient> findExactByNameNoAccent(@Param("name") String name, @Param("category") IngredientCategory category);

    /** Mirrors the "prefix_name" stage of IngredientORM.find_suggestion_candidates. */
    @Query("SELECT i FROM Ingredient i WHERE i.deleted = false AND i.nameNoAccent LIKE CONCAT(:prefix, '%') "
            + "AND (:category IS NULL OR i.category = :category)")
    List<Ingredient> findPrefixByNameNoAccent(@Param("prefix") String prefix, @Param("category") IngredientCategory category);

    /**
     * Mirrors the non-Postgres fallback branch of IngredientORM.find_suggestion_candidates
     * ({@code Ingredient.objects.filter(deleted=False)[:200]}, default Meta ordering by name) —
     * see PORT-NOTE on IngredientService.findSuggestionCandidates for why this port always takes
     * that branch instead of depending on the pg_trgm extension.
     */
    @Query("SELECT i FROM Ingredient i WHERE i.deleted = false AND (:category IS NULL OR i.category = :category) "
            + "ORDER BY i.name")
    List<Ingredient> findCandidatesForFuzzyMatch(@Param("category") IngredientCategory category, Pageable pageable);
}
