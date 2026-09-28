package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Mirrors IngredientORM.list_ingredient_suggestions' filter combination. */
public final class IngredientSuggestionSpecifications {

    private IngredientSuggestionSpecifications() {
    }

    public static Specification<IngredientSuggestion> filter(
            IngredientImportStatus status, String search, IngredientCategory category, CustomUser createdBy) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(root.get("suggestedNameNoAccent"), "%" + RemoveAccents.apply(search) + "%"));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("suggestedCategory"), category));
            }
            if (createdBy != null) {
                predicates.add(cb.equal(root.get("createdBy"), createdBy));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
