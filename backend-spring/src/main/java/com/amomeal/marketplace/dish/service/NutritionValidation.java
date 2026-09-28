package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.dish.exception.IngredientWeightOutOfBoundsException;
import com.amomeal.marketplace.dish.exception.NutritionOutlierException;
import com.amomeal.marketplace.dish.exception.PortionWeightOutOfBoundsException;
import com.amomeal.marketplace.dish.repository.DishIngredientRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verbatim port of ../../backend/dish/services/validation.py — the three-layer
 * nutrition validation for DishIngredient data.
 *
 * <ul>
 *   <li><b>Layer 1</b> {@link #validateIngredientWeight} — called on every
 *       DishIngredient create/update (from
 *       {@link DishNutritionService#processIngredientPipeline}). Hard fail if
 *       the weight is outside its category's bounds.</li>
 *   <li><b>Layer 2</b> {@link #validatePortionWeight} — total portion weight
 *       inside {@code [MIN_PORTION_WEIGHT, MAX_PORTION_WEIGHT]}.</li>
 *   <li><b>Layer 3</b> {@link #validateNutritionOutcome} — per-serving nutrition
 *       totals inside {@code NUTRITION_BOUNDS}; skipped entirely when any
 *       ingredient is missing a nutrition value (never reject for missing
 *       data).</li>
 * </ul>
 *
 * <h2>PORT-NOTE — Layers 2 and 3 are unreachable in Django today</h2>
 * A grep of the whole Django tree shows {@code validate_portion_weight},
 * {@code validate_nutrition_outcome} and their {@code validate_on_publish}
 * convenience wrapper are defined but <b>never called from anywhere</b> — only
 * Layer 1 is actually wired (into the ingredient-composition pipeline). The
 * docstrings say they are meant to run "when the chef publishes the dish", but
 * no publish endpoint invokes them. They are ported faithfully and exposed as
 * public methods here (so the future `menu`/publish flow can wire them up with
 * a one-liner), and are covered by unit tests, but — exactly like Django — no
 * controller path in this port calls them either. Wiring them into, say,
 * dish status changes would be a behavior change, not a port.
 */
@Service
@RequiredArgsConstructor
public class NutritionValidation {

    private final DishRepository dishRepository;
    private final DishIngredientRepository dishIngredientRepository;

    // =====================================================================
    // Layer 1 — per-ingredient weight (pure; no DB access)
    // =====================================================================

    /**
     * Mirrors {@code validate_ingredient_weight}. Raises
     * {@link IngredientWeightOutOfBoundsException} when weight &lt;= 0, or when it
     * is outside the bounds configured for the ingredient's category.
     *
     * @param ingredientCategory null for a custom (not-yet-in-USDA) ingredient —
     *                           Django then only enforces {@code weight > 0} and
     *                           reports the category as {@code "UNKNOWN"}.
     */
    public static void validateIngredientWeight(double weight, IngredientCategory ingredientCategory,
                                                String ingredientName) {
        String name = (ingredientName == null || ingredientName.isEmpty()) ? "?" : ingredientName;

        if (weight <= 0) {
            throw new IngredientWeightOutOfBoundsException(
                    name, weight,
                    ingredientCategory == null ? "UNKNOWN" : ingredientCategory.name(),
                    0.001, null);
        }

        if (ingredientCategory != null) {
            IngredientBounds.Bounds bounds = IngredientBounds.CATEGORY_BOUNDS.get(ingredientCategory);
            if (bounds != null && (weight < bounds.minG() || weight > bounds.maxG())) {
                throw new IngredientWeightOutOfBoundsException(
                        name, weight, ingredientCategory.name(), bounds.minG(), bounds.maxG());
            }
        }
    }

    // =====================================================================
    // Layer 2 — total portion weight
    // =====================================================================

    /** Mirrors {@code validate_portion_weight}. */
    @Transactional(readOnly = true)
    public void validatePortionWeight(UUID dishUid) {
        Double sum = dishIngredientRepository.sumWeightOfDish(dishUid);
        double total = sum == null ? 0.0 : sum;

        if (total < IngredientBounds.MIN_PORTION_WEIGHT) {
            throw new PortionWeightOutOfBoundsException(total, IngredientBounds.MIN_PORTION_WEIGHT, null);
        }
        if (total > IngredientBounds.MAX_PORTION_WEIGHT) {
            throw new PortionWeightOutOfBoundsException(total, null, IngredientBounds.MAX_PORTION_WEIGHT);
        }
    }

    // =====================================================================
    // Layer 3 — per-serving nutritional outcome
    // =====================================================================

    /** Mirrors {@code validate_nutrition_outcome}. */
    @Transactional(readOnly = true)
    public void validateNutritionOutcome(UUID dishUid, int servingSize) {
        List<DishIngredient> items = dishIngredientRepository.findAllByDishUidAndDeletedFalse(dishUid);
        validateNutritionOutcome(items, servingSize);
    }

    /**
     * Pure variant of Layer 3 (same algorithm, already-loaded rows) — kept public
     * so it can be unit-tested and reused without a second DB round trip.
     */
    public static void validateNutritionOutcome(List<DishIngredient> items, int servingSizeRaw) {
        int servingSize = Math.max(servingSizeRaw, 1);

        if (items.isEmpty()) {
            return;
        }

        Map<String, Double> totals = new LinkedHashMap<>();
        for (String key : IngredientBounds.NUTRITION_BOUNDS.keySet()) {
            totals.put(key, 0.0);
        }

        for (DishIngredient row : items) {
            for (Map.Entry<String, String> entry : IngredientBounds.NUTRIENT_FIELD_MAP.entrySet()) {
                Double value = NutrientFields.get(row, entry.getValue());
                if (value == null) {
                    // Missing data for one ingredient -> skip Layer 3 entirely (Django's behavior).
                    return;
                }
                totals.merge(entry.getKey(), value, Double::sum);
            }
        }

        List<Map<String, Object>> violations = new ArrayList<>();
        for (Map.Entry<String, Double> bound : IngredientBounds.NUTRITION_BOUNDS.entrySet()) {
            double total = totals.get(bound.getKey());
            double perServing = total / servingSize;
            if (perServing > bound.getValue()) {
                Map<String, Object> violation = new LinkedHashMap<>();
                violation.put("nutrient", bound.getKey());
                violation.put("per_serving", round1(perServing));
                violation.put("total_dish", round1(total));
                violation.put("max_allowed_per_serving", bound.getValue());
                violations.add(violation);
            }
        }

        if (!violations.isEmpty()) {
            throw new NutritionOutlierException(violations, servingSize);
        }
    }

    /**
     * Mirrors {@code validate_on_publish} — Layer 2 + Layer 3 together. See the
     * class-level PORT-NOTE: nothing calls this in Django, and nothing calls it
     * here either.
     */
    @Transactional(readOnly = true)
    public void validateOnPublish(UUID dishUid) {
        Dish dish = dishRepository.findByUid(dishUid).orElse(null);
        if (dish == null) {
            return;
        }
        validatePortionWeight(dishUid);
        validateNutritionOutcome(dishUid, dish.getServingSize());
    }

    /** Django rounds with Python semantics (half-to-even) — reuse the shared helper. */
    private static double round1(double value) {
        return DishNutritionService.pyRound(value, 1);
    }
}
