package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.ingredient.entity.IngredientCategory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verbatim port of ../../backend/dish/constants/ingredient_bounds.py.
 *
 * <p>Layer 1 — per-ingredient-category weight bounds: block junk data (typos,
 * wrong units). Not a nutrition standard.
 * <p>Layer 2 — portion total bounds: block absurd totals, while still supporting
 * combo/hotpot/set meals (MAX 5000 g).
 * <p>Layer 3 — per-serving nutritional outcome ceilings (WHO Dietary Reference
 * Intakes as the reference point). Skipped entirely if any ingredient is
 * missing nutrition data — never reject for lack of information.
 *
 * <p>All numeric values are reproduced exactly; changing any of them changes
 * what the API rejects, so they are deliberately kept in one place and covered
 * by {@code NutritionValidationTest}.
 */
public final class IngredientBounds {

    private IngredientBounds() {
    }

    /** (min_g, max_g) per single DishIngredient.weight entry. */
    public record Bounds(double minG, double maxG) {
    }

    /**
     * Django keys this map by the raw category string. Ported keyed by the enum
     * for type safety — the names match {@code IngredientCategoryEnum} 1:1.
     * A category absent from this map is only checked for {@code weight > 0}.
     */
    public static final Map<IngredientCategory, Bounds> CATEGORY_BOUNDS;

    static {
        Map<IngredientCategory, Bounds> m = new LinkedHashMap<>();
        m.put(IngredientCategory.SPICE, new Bounds(0.1, 60));          // muối, đường, tiêu, nước mắm (~50ml)
        m.put(IngredientCategory.OILFATBUTTER, new Bounds(1, 150));    // dầu ăn, bơ, mỡ
        m.put(IngredientCategory.PROTEIN, new Bounds(5, 1000));        // thịt, cá, hải sản, đậu phụ, trứng
        m.put(IngredientCategory.GRAIN, new Bounds(5, 1500));          // cơm, bún, mì, bánh mì, khoai
        m.put(IngredientCategory.VEGETABLE, new Bounds(2, 1500));      // rau, củ
        m.put(IngredientCategory.FRUIT, new Bounds(2, 1000));          // trái cây trong món, sinh tố
        m.put(IngredientCategory.MILK, new Bounds(5, 1000));           // sữa, kem, phô mai
        CATEGORY_BOUNDS = Collections.unmodifiableMap(m);
    }

    /** Layer 2: gram — smallest sensible portion (a dipping sauce side). */
    public static final double MIN_PORTION_WEIGHT = 10;

    /** Layer 2: gram — supports a family hotpot/combo (~4 people). */
    public static final double MAX_PORTION_WEIGHT = 5000;

    /**
     * Layer 3: per-serving ceilings (total / dish.serving_size), keyed by the
     * same bound names Django uses in the violation payload.
     */
    public static final Map<String, Double> NUTRITION_BOUNDS;

    /** Layer 3: NUTRITION_BOUNDS key -&gt; DishIngredient field name. */
    public static final Map<String, String> NUTRIENT_FIELD_MAP;

    static {
        Map<String, Double> bounds = new LinkedHashMap<>();
        bounds.put("calories", 5000.0);   // kcal — WHO RDI ~2000-2500; 5000 is a clear ceiling
        bounds.put("protein_g", 300.0);   // g    — biological absorption limit ~200-300 g/day
        bounds.put("fat_g", 300.0);       // g    — 300 g fat = 2700 kcal from fat alone
        bounds.put("carb_g", 600.0);      // g    — max carbs WHO; 600 is a clear ceiling
        bounds.put("sodium_mg", 10000.0); // mg   — WHO max 2000 mg/day; 10000 is clearly abnormal
        // LinkedHashMap + unmodifiableMap (NOT Map.copyOf) — iteration order defines the
        // order of the "violations" list in the Layer 3 error payload, and Map.copyOf
        // deliberately randomizes it.
        NUTRITION_BOUNDS = Collections.unmodifiableMap(bounds);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("calories", "energy");
        fields.put("protein_g", "protein");
        fields.put("fat_g", "lipid");
        fields.put("carb_g", "carbohydrate");
        fields.put("sodium_mg", "natri");
        NUTRIENT_FIELD_MAP = Collections.unmodifiableMap(fields);
    }
}
