package com.amomeal.marketplace.ingredient.repository;

import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientAlias;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngredientAliasRepository extends JpaRepository<IngredientAlias, UUID> {

    Optional<IngredientAlias> findByAliasNoAccent(String aliasNoAccent);

    List<IngredientAlias> findAllByOrderByAliasAsc();

    List<IngredientAlias> findAllByAliasNoAccentContainingOrderByAliasAsc(String aliasNoAccent);

    List<IngredientAlias> findAllByActiveTrueAndIngredientDeletedFalseAndIngredientIn(List<Ingredient> ingredients);

    boolean existsByIngredient(Ingredient ingredient);

    /** Mirrors the "alias_exact" stage of IngredientORM.find_suggestion_candidates. */
    @Query("SELECT a FROM IngredientAlias a WHERE a.active = true AND a.ingredient.deleted = false "
            + "AND a.aliasNoAccent = :alias AND (:category IS NULL OR a.ingredient.category = :category)")
    List<IngredientAlias> findExactActive(@Param("alias") String alias, @Param("category") IngredientCategory category);

    /** Mirrors the "prefix_alias" stage of IngredientORM.find_suggestion_candidates. */
    @Query("SELECT a FROM IngredientAlias a WHERE a.active = true AND a.ingredient.deleted = false "
            + "AND a.aliasNoAccent LIKE CONCAT(:prefix, '%') AND (:category IS NULL OR a.ingredient.category = :category)")
    List<IngredientAlias> findPrefixActive(@Param("prefix") String prefix, @Param("category") IngredientCategory category);
}
