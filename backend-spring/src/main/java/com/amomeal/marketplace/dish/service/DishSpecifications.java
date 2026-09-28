package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.dish.entity.DishLocation;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Criteria-API port of ../../backend/dish/schemas/requests.py::FilterDishSchema
 * (its {@code filter_chef_id}/{@code filter_location_id}/{@code filter_search}/
 * {@code filter_categories} methods) plus the allergy HIDE exclusion that
 * ../../backend/dish/orm/dish.py::get_all_dishes applies on top.
 */
public final class DishSpecifications {

    private DishSpecifications() {
    }

    /** Every list query starts from {@code Dish.objects.filter(deleted=False)}. */
    public static Specification<Dish> notDeleted() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    /** Post-port suspension enforcement: hide DISH_LOCK-ed dishes from customer-facing lists. */
    public static Specification<Dish> notSuspended() {
        return (root, query, cb) -> cb.isFalse(root.get("suspended"));
    }

    /** Django: {@code filter_chef_id} -&gt; {@code Q(owner_id=value)}. */
    public static Specification<Dish> ownedBy(Long chefId) {
        if (chefId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("owner").get("id"), chefId);
    }

    /**
     * Django: {@code filter_location_id} -&gt;
     * {@code Q(location_id=v) | Q(location__parent_id=v) | Q(location__parent__parent_id=v)}
     * (match the country, or anything under the given subregion/region).
     */
    public static Specification<Dish> inLocation(Long locationId) {
        if (locationId == null) {
            return null;
        }
        return (root, query, cb) -> {
            Join<Dish, DishLocation> location = root.join("location", jakarta.persistence.criteria.JoinType.LEFT);
            Join<DishLocation, DishLocation> parent = location.join("parent", jakarta.persistence.criteria.JoinType.LEFT);
            Join<DishLocation, DishLocation> grandparent = parent.join("parent", jakarta.persistence.criteria.JoinType.LEFT);
            return cb.or(
                    cb.equal(location.get("id"), locationId),
                    cb.equal(parent.get("id"), locationId),
                    cb.equal(grandparent.get("id"), locationId));
        };
    }

    /** Django: {@code filter_search} -&gt; {@code Q(name_no_accent__icontains=remove_accents(value))}. */
    public static Specification<Dish> nameContains(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        String needle = RemoveAccents.apply(search).toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.like(cb.lower(root.get("nameNoAccent")), "%" + needle + "%");
    }

    /**
     * Django: {@code filter_categories} -&gt; {@code Q(category__in=value.split(","))}.
     * An unparseable token is dropped rather than blowing up on the enum
     * conversion; if that leaves nothing, no category filter is applied.
     */
    public static Specification<Dish> inCategories(String categories) {
        if (!StringUtils.hasText(categories)) {
            return null;
        }
        List<DishCategory> parsed = new ArrayList<>();
        for (String token : Arrays.asList(categories.split(","))) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(DishCategory.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // unknown category token -> ignore
            }
        }
        if (parsed.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("category").in(parsed);
    }

    /**
     * Django: the HIDE branch of get_all_dishes —
     * {@code query.exclude(dish_ingredient_fk_dish__ingredient_id__in=allergic_ids,
     * dish_ingredient_fk_dish__deleted=False).distinct()}.
     *
     * <p>Expressed as a correlated NOT EXISTS instead of a join + DISTINCT, which
     * is the same result set but keeps COUNT(*) pagination exact.
     */
    public static Specification<Dish> withoutAllergens(List<UUID> allergicIngredientUids) {
        if (allergicIngredientUids == null || allergicIngredientUids.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<DishIngredient> di = subquery.from(DishIngredient.class);
            Join<DishIngredient, Ingredient> ingredient = di.join("ingredient");
            subquery.select(cb.literal(1)).where(
                    cb.equal(di.get("dish"), root),
                    cb.isFalse(di.get("deleted")),
                    ingredient.get("uid").in(allergicIngredientUids));
            return cb.not(cb.exists(subquery));
        };
    }

    /** Combines the non-null specs with AND (Spring Data 4 dropped {@code Specification.where(null)}). */
    @SafeVarargs
    public static Specification<Dish> allOf(Specification<Dish>... specs) {
        Specification<Dish> combined = null;
        for (Specification<Dish> spec : specs) {
            if (spec == null) {
                continue;
            }
            combined = (combined == null) ? spec : combined.and(spec);
        }
        return combined == null ? (root, query, cb) -> cb.conjunction() : combined;
    }

    /** Small helper so callers can build predicates without importing the Criteria API. */
    public static Predicate[] noPredicates() {
        return new Predicate[0];
    }
}
