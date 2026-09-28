package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mirrors ../../backend/ingredient/schemas/requests.py::FilterIngredientSchema
 * (search -> name_no_accent__icontains, categories -> comma-separated
 * category__in).
 */
public final class IngredientSpecifications {

    private IngredientSpecifications() {
    }

    public static Specification<Ingredient> filter(String search, String categories) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(root.get("nameNoAccent"), "%" + RemoveAccents.apply(search) + "%"));
            }
            if (StringUtils.hasText(categories)) {
                List<IngredientCategory> values = Arrays.stream(categories.split(","))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .map(String::toUpperCase)
                        .map(IngredientCategory::valueOf)
                        .toList();
                if (!values.isEmpty()) {
                    predicates.add(root.get("category").in(values));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
