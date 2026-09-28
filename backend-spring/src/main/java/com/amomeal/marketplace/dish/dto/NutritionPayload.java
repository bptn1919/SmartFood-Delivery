package com.amomeal.marketplace.dish.dto;

/**
 * The slice of a request DTO that the nutrition pipeline reads, replacing
 * Python's duck-typed {@code getattr(payload, field, None)} over the various
 * {@code DishIngredient*Schema}s in
 * ../../backend/dish/schemas/requests.py.
 *
 * <p>Crucially, {@link #nutrient(String)} returns {@code null} for a field the
 * concrete schema simply does not declare — exactly like {@code getattr(...,
 * None)} does in Django. That is not an oversight: e.g.
 * {@code DishIngredientCreateSchema} has no {@code cholesterol}/{@code retinol}/
 * {@code caroten}/{@code vitamin_b_1} fields, so those are always "no override"
 * on that endpoint and fall through to the USDA-scaled value.
 */
public interface NutritionPayload {

    /** Django: {@code payload.weight} (always required on every schema). */
    double weight();

    /**
     * @param field one of {@code NutrientFields.DISH_NUTRIENT_FIELDS}
     * @return the chef-entered override, or null when absent/not declared
     */
    Double nutrient(String field);

    /** Django: {@code getattr(payload, "custom_name", "")}. */
    default String customName() {
        return null;
    }
}
