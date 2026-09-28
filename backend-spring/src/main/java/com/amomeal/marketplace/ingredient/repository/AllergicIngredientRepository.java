package com.amomeal.marketplace.ingredient.repository;

import com.amomeal.marketplace.ingredient.entity.AllergicIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AllergicIngredientRepository extends JpaRepository<AllergicIngredient, UUID> {

    Optional<AllergicIngredient> findByUserAndIngredient(CustomUser user, Ingredient ingredient);

    List<AllergicIngredient> findAllByUserAndDeletedFalse(CustomUser user);

    boolean existsByIngredient(Ingredient ingredient);
}
