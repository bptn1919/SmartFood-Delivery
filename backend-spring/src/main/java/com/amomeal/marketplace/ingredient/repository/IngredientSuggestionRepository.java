package com.amomeal.marketplace.ingredient.repository;

import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface IngredientSuggestionRepository extends JpaRepository<IngredientSuggestion, UUID>,
        JpaSpecificationExecutor<IngredientSuggestion> {

    Optional<IngredientSuggestion> findByUidAndCreatedBy(UUID uid, CustomUser createdBy);

    Optional<IngredientSuggestion> findFirstBySuggestedNameIgnoreCaseAndStatusAndCreatedBy(
            String suggestedName, IngredientImportStatus status, CustomUser createdBy);

    boolean existsByIngredient(com.amomeal.marketplace.ingredient.entity.Ingredient ingredient);

    java.util.List<IngredientSuggestion> findAllByStatusAndDeletedFalse(IngredientImportStatus status);
}
