package com.amomeal.marketplace.ingredient.repository;

import com.amomeal.marketplace.ingredient.entity.FavouriteIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavouriteIngredientRepository extends JpaRepository<FavouriteIngredient, UUID> {

    Optional<FavouriteIngredient> findByUserAndIngredient(CustomUser user, Ingredient ingredient);

    List<FavouriteIngredient> findAllByUserAndDeletedFalse(CustomUser user);

    boolean existsByIngredient(Ingredient ingredient);
}
