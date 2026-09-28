package com.amomeal.marketplace.dish.repository;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DishIngredientRepository extends JpaRepository<DishIngredient, UUID> {

    Optional<DishIngredient> findByUidAndDeletedFalse(UUID uid);

    /** Mirrors DishORM.get_all_ingredients_of_dish_for_customers. */
    @Query("SELECT di FROM DishIngredient di LEFT JOIN FETCH di.ingredient "
            + "WHERE di.dish = :dish AND di.deleted = false")
    List<DishIngredient> findAllOfDish(@Param("dish") Dish dish);

    /** Mirrors DishORM.get_all_ingredients_of_dish_for_chefs (ordered by -updated_at). */
    @Query("SELECT di FROM DishIngredient di LEFT JOIN FETCH di.ingredient "
            + "WHERE di.dish = :dish AND di.deleted = false ORDER BY di.updatedAt DESC")
    List<DishIngredient> findAllOfDishForChef(@Param("dish") Dish dish);

    /** Mirrors DishORM.dish_has_ingredient. */
    boolean existsByDishAndIngredientAndDeletedFalse(Dish dish, Ingredient ingredient);

    /** Mirrors DishORM.get_dish_ingredient_by_dish_and_ingredient. */
    Optional<DishIngredient> findFirstByDishAndIngredientAndDeletedFalse(Dish dish, Ingredient ingredient);

    /** Mirrors DishORM.dish_has_ingredient_suggestion. */
    boolean existsByDishAndSuggestionAndDeletedFalse(Dish dish, IngredientSuggestion suggestion);

    /** Mirrors DishORM.dish_has_custom_ingredient (custom_name__iexact, ingredient IS NULL, same creator). */
    @Query("SELECT COUNT(di) > 0 FROM DishIngredient di WHERE di.dish = :dish AND di.ingredient IS NULL "
            + "AND LOWER(di.customName) = LOWER(:customName) AND di.createdBy = :createdBy AND di.deleted = false")
    boolean existsCustomIngredient(@Param("dish") Dish dish, @Param("customName") String customName,
                                   @Param("createdBy") CustomUser createdBy);

    /** Mirrors DishORM.get_one_dish_ingredient_by_suggestion_and_user (latest by -created_at). */
    Optional<DishIngredient> findFirstBySuggestionAndCreatedByOrderByCreatedAtDesc(
            IngredientSuggestion suggestion, CustomUser createdBy);

    /** Mirrors DishORM.get_dish_ingredient_by_suggestion_uid. */
    Optional<DishIngredient> findFirstBySuggestionUidAndDeletedFalse(UUID suggestionUid);

    /** Mirrors DishORM.count_ingredients_of_dish. */
    long countByDishAndDeletedFalse(Dish dish);

    /** Dish-side half of the allergy hide/warn filters. */
    @Query("SELECT DISTINCT di.dish.uid FROM DishIngredient di WHERE di.deleted = false "
            + "AND di.dish.uid IN :dishUids AND di.ingredient.uid IN :ingredientUids")
    List<UUID> findDishUidsContainingIngredients(@Param("dishUids") List<UUID> dishUids,
                                                 @Param("ingredientUids") List<UUID> ingredientUids);

    @Query("SELECT DISTINCT di.ingredient.name FROM DishIngredient di WHERE di.deleted = false "
            + "AND di.dish.uid = :dishUid AND di.ingredient.uid IN :ingredientUids")
    List<String> findAllergenIngredientNames(@Param("dishUid") UUID dishUid,
                                             @Param("ingredientUids") List<UUID> ingredientUids);

    /** Layer 2 of the nutrition validation — SUM(weight) over a dish's live ingredient rows. */
    @Query("SELECT COALESCE(SUM(di.weight), 0.0) FROM DishIngredient di "
            + "WHERE di.dish.uid = :dishUid AND di.deleted = false")
    Double sumWeightOfDish(@Param("dishUid") UUID dishUid);

    List<DishIngredient> findAllByDishUidAndDeletedFalse(UUID dishUid);
}
