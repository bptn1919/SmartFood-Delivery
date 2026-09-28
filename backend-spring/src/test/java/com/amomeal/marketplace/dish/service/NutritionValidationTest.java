package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.dish.exception.IngredientWeightOutOfBoundsException;
import com.amomeal.marketplace.dish.exception.NutritionOutlierException;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the three-layer nutrition validation ported from
 * ../../backend/dish/services/validation.py +
 * ../../backend/dish/constants/ingredient_bounds.py. Thresholds are asserted
 * against the literal Django numbers, so a future edit to
 * {@link IngredientBounds} that changes what the API rejects fails here.
 */
class NutritionValidationTest {

    // =====================================================================
    // Layer 1 — per-ingredient weight bounds
    // =====================================================================

    @Test
    void layer1_rejectsNonPositiveWeight_withMinOnlyDetail() {
        assertThatThrownBy(() -> NutritionValidation.validateIngredientWeight(0, IngredientCategory.PROTEIN, "thịt bò"))
                .isInstanceOf(IngredientWeightOutOfBoundsException.class)
                .satisfies(ex -> {
                    IngredientWeightOutOfBoundsException e = (IngredientWeightOutOfBoundsException) ex;
                    assertThat(e.getHttpStatus().value()).isEqualTo(422);
                    assertThat(e.getMessageCode()).isEqualTo("INGREDIENT_WEIGHT_OUT_OF_BOUNDS");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detail = (Map<String, Object>) e.getDetail();
                    assertThat(detail).containsEntry("ingredient", "thịt bò")
                            .containsEntry("entered_weight_g", 0.0)
                            .containsEntry("category", "PROTEIN")
                            .containsEntry("min_allowed_g", 0.001)
                            // Django passes max_g=None for this branch -> key omitted entirely.
                            .doesNotContainKey("max_allowed_g");
                });
    }

    @Test
    void layer1_unknownCategoryOnlyChecksPositivity() {
        // A null category (custom ingredient) is reported as "UNKNOWN" and only the
        // weight > 0 rule applies — a 9 kg custom ingredient passes, like in Django.
        assertThatThrownBy(() -> NutritionValidation.validateIngredientWeight(-1, null, ""))
                .isInstanceOf(IngredientWeightOutOfBoundsException.class)
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detail =
                            (Map<String, Object>) ((IngredientWeightOutOfBoundsException) ex).getDetail();
                    assertThat(detail).containsEntry("category", "UNKNOWN")
                            // Django: ingredient_name or "?"
                            .containsEntry("ingredient", "?");
                });

        assertThatCode(() -> NutritionValidation.validateIngredientWeight(9000, null, "custom"))
                .doesNotThrowAnyException();
    }

    @Test
    void layer1_enforcesExactCategoryBounds() {
        // SPICE = (0.1, 60)
        assertThatCode(() -> NutritionValidation.validateIngredientWeight(0.1, IngredientCategory.SPICE, "muối"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NutritionValidation.validateIngredientWeight(60, IngredientCategory.SPICE, "muối"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> NutritionValidation.validateIngredientWeight(60.01, IngredientCategory.SPICE, "muối"))
                .isInstanceOf(IngredientWeightOutOfBoundsException.class);
        assertThatThrownBy(() -> NutritionValidation.validateIngredientWeight(0.09, IngredientCategory.SPICE, "muối"))
                .isInstanceOf(IngredientWeightOutOfBoundsException.class);

        // PROTEIN = (5, 1000)
        assertThatCode(() -> NutritionValidation.validateIngredientWeight(1000, IngredientCategory.PROTEIN, "bò"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> NutritionValidation.validateIngredientWeight(1000.5, IngredientCategory.PROTEIN, "bò"))
                .isInstanceOf(IngredientWeightOutOfBoundsException.class);
    }

    @Test
    void layer1_boundsTableMatchesDjangoExactly() {
        Map<IngredientCategory, IngredientBounds.Bounds> b = IngredientBounds.CATEGORY_BOUNDS;
        assertThat(b.get(IngredientCategory.SPICE)).isEqualTo(new IngredientBounds.Bounds(0.1, 60));
        assertThat(b.get(IngredientCategory.OILFATBUTTER)).isEqualTo(new IngredientBounds.Bounds(1, 150));
        assertThat(b.get(IngredientCategory.PROTEIN)).isEqualTo(new IngredientBounds.Bounds(5, 1000));
        assertThat(b.get(IngredientCategory.GRAIN)).isEqualTo(new IngredientBounds.Bounds(5, 1500));
        assertThat(b.get(IngredientCategory.VEGETABLE)).isEqualTo(new IngredientBounds.Bounds(2, 1500));
        assertThat(b.get(IngredientCategory.FRUIT)).isEqualTo(new IngredientBounds.Bounds(2, 1000));
        assertThat(b.get(IngredientCategory.MILK)).isEqualTo(new IngredientBounds.Bounds(5, 1000));
        assertThat(IngredientBounds.MIN_PORTION_WEIGHT).isEqualTo(10);
        assertThat(IngredientBounds.MAX_PORTION_WEIGHT).isEqualTo(5000);
        assertThat(IngredientBounds.NUTRITION_BOUNDS).containsExactly(
                Map.entry("calories", 5000.0),
                Map.entry("protein_g", 300.0),
                Map.entry("fat_g", 300.0),
                Map.entry("carb_g", 600.0),
                Map.entry("sodium_mg", 10000.0));
    }

    // =====================================================================
    // Layer 3 — per-serving nutrition outcome (pure variant)
    // =====================================================================

    private DishIngredient row(Double energy, Double protein, Double lipid, Double carb, Double natri) {
        return DishIngredient.builder()
                .energy(energy).protein(protein).lipid(lipid).carbohydrate(carb).natri(natri)
                .build();
    }

    @Test
    void layer3_noIngredients_skips() {
        assertThatCode(() -> NutritionValidation.validateNutritionOutcome(List.of(), 1))
                .doesNotThrowAnyException();
    }

    @Test
    void layer3_missingAnyValue_skipsEntirely() {
        // 60000 kcal is way past the 5000 ceiling, but one null natri means "no data" ->
        // Django returns early and validates nothing at all.
        List<DishIngredient> items = List.of(
                row(60000.0, 10.0, 10.0, 10.0, null));
        assertThatCode(() -> NutritionValidation.validateNutritionOutcome(items, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void layer3_withinBounds_passes() {
        List<DishIngredient> items = List.of(
                row(400.0, 20.0, 10.0, 50.0, 800.0),
                row(300.0, 15.0, 5.0, 40.0, 600.0));
        assertThatCode(() -> NutritionValidation.validateNutritionOutcome(items, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void layer3_reportsEveryViolation_withRoundedPerServingAndTotals() {
        // totals: 6000 kcal, 400 g protein, 20 g fat, 100 g carb, 12000 mg sodium
        List<DishIngredient> items = List.of(
                row(3000.0, 200.0, 10.0, 50.0, 6000.0),
                row(3000.55, 200.0, 10.0, 50.0, 6000.0));

        assertThatThrownBy(() -> NutritionValidation.validateNutritionOutcome(items, 1))
                .isInstanceOf(NutritionOutlierException.class)
                .satisfies(ex -> {
                    NutritionOutlierException e = (NutritionOutlierException) ex;
                    assertThat(e.getHttpStatus().value()).isEqualTo(422);
                    assertThat(e.getMessageCode()).isEqualTo("NUTRITION_OUTLIER");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detail = (Map<String, Object>) e.getDetail();
                    assertThat(detail).containsEntry("serving_size", 1);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> violations = (List<Map<String, Object>>) detail.get("violations");
                    // calories, protein_g, sodium_mg breach; fat_g (20) and carb_g (100) do not.
                    assertThat(violations).hasSize(3);
                    assertThat(violations.get(0)).containsEntry("nutrient", "calories")
                            .containsEntry("per_serving", 6000.6)
                            .containsEntry("total_dish", 6000.6)
                            .containsEntry("max_allowed_per_serving", 5000.0);
                    assertThat(violations.get(1)).containsEntry("nutrient", "protein_g");
                    assertThat(violations.get(2)).containsEntry("nutrient", "sodium_mg");
                });
    }

    @Test
    void layer3_servingSizeDividesTheTotals() {
        // Same 6000 kcal / 400 g protein / 12000 mg sodium dish, but it feeds 4:
        // per serving = 1500 / 100 / 3000 -> all within bounds.
        List<DishIngredient> items = List.of(
                row(3000.0, 200.0, 10.0, 50.0, 6000.0),
                row(3000.0, 200.0, 10.0, 50.0, 6000.0));
        assertThatCode(() -> NutritionValidation.validateNutritionOutcome(items, 4))
                .doesNotThrowAnyException();

        // serving_size is floored at 1 (Django: max(serving_size, 1)), so 0 still trips.
        assertThatThrownBy(() -> NutritionValidation.validateNutritionOutcome(items, 0))
                .isInstanceOf(NutritionOutlierException.class);
    }
}
