package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.dto.DishIngredientCreateRequest;
import com.amomeal.marketplace.dish.dto.DishIngredientSuggestionRequest;
import com.amomeal.marketplace.dish.dto.WarningResponse;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.amomeal.marketplace.ingredient.exception.NutritionValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the nutrition compute/analyze/confidence pipeline ported from
 * ../../backend/dish/services/__init__.py::DishService.
 *
 * <p>Every expected number below was computed independently by running the
 * original Python formulas (not by reading this port's output), so these assert
 * behavioral parity with Django rather than just "the code runs".
 */
class DishNutritionServiceTest {

    private static final double EPS = 1e-12;

    private final DishNutritionService service = new DishNutritionService();

    // =====================================================================
    // Scaling
    // =====================================================================

    @Test
    void scaledValue_scalesByWeightOverReferenceWeight_defaultingReferenceTo100() {
        assertThat(DishNutritionService.scaledValue(50.0, 100.0, 200.0)).isEqualTo(100.0);
        // reference weight missing or <= 0 -> Django falls back to 100 g
        assertThat(DishNutritionService.scaledValue(50.0, null, 50.0)).isEqualTo(25.0);
        assertThat(DishNutritionService.scaledValue(50.0, 0.0, 50.0)).isEqualTo(25.0);
        assertThat(DishNutritionService.scaledValue(null, 100.0, 200.0)).isNull();
    }

    // =====================================================================
    // Severity formulas
    // =====================================================================

    @Test
    void macroSeverity_matchesDjango() {
        assertThat(DishNutritionService.computeMacroSeverity(10, 5, 15, 0)).isZero();      // weight <= 0
        assertThat(DishNutritionService.computeMacroSeverity(10, 5, 15, 100)).isZero();    // density 30 -> safe zone
        assertThat(DishNutritionService.computeMacroSeverity(2, 2, 1, 100)).isZero();      // density 5 -> safe zone edge
        // density 35 -> x = (35-30)/30, x**1.5
        assertThat(DishNutritionService.computeMacroSeverity(10, 5, 20, 100))
                .isCloseTo(0.06804138174397717, within(EPS));
        // density 3 -> x = (5-3)/5, x**1.5
        assertThat(DishNutritionService.computeMacroSeverity(1, 1, 1, 100))
                .isCloseTo(0.2529822128134704, within(EPS));
        // hard fails
        assertThat(DishNutritionService.computeMacroSeverity(60, 30, 30, 100)).isEqualTo(1.0); // density 120
        assertThat(DishNutritionService.computeMacroSeverity(0.1, 0.1, 0.1, 100)).isEqualTo(1.0); // density 0.3
    }

    @Test
    void energySeverity_matchesDjango() {
        // expected kcal = 4p + 9l + 4c = 145 for (10, 5, 15)
        assertThat(DishNutritionService.computeEnergySeverity(145, 10, 5, 15)).isZero();
        assertThat(DishNutritionService.computeEnergySeverity(0, 0, 0, 0)).isZero();   // expected <= 0
        assertThat(DishNutritionService.computeEnergySeverity(43, 10, 5, 15)).isEqualTo(1.0);  // ratio < 0.4
        assertThat(DishNutritionService.computeEnergySeverity(300, 10, 5, 15)).isEqualTo(1.0); // ratio > 1.8
        // ratio 0.5 -> |ln 0.5|
        assertThat(DishNutritionService.computeEnergySeverity(72.5, 10, 5, 15))
                .isCloseTo(0.6931471805599453, within(EPS));
        // expected 165, energy 200 -> ratio 1.2121...
        assertThat(DishNutritionService.computeEnergySeverity(200, 10, 5, 20))
                .isCloseTo(0.1923718926474561, within(EPS));
    }

    @Test
    void ratioSeverity_matchesDjango_includingTheExpectedZeroBranch() {
        assertThat(DishNutritionService.ratioSeverity(0.5, 0.0)).isZero();     // <= ZERO_OVERRIDE_TOLERANCE
        assertThat(DishNutritionService.ratioSeverity(0.4, null)).isZero();
        // log10(actual + 1) / 4
        assertThat(DishNutritionService.ratioSeverity(10, 0.0)).isCloseTo(0.2603481712895563, within(EPS));
        // |ln(2)| / 2.5
        assertThat(DishNutritionService.ratioSeverity(20, 10.0)).isCloseTo(0.2772588722239781, within(EPS));
        assertThat(DishNutritionService.ratioSeverity(10, 10.0)).isZero();
    }

    @Test
    void outlierSeverity_scoresAboveSafeRatio_andThrowsPastHardRatio() {
        // 900 kcal per 100 g: limit 900, safe 720 -> x = 180/1980
        DishNutritionService.OutlierSeverity result =
                DishNutritionService.computeOutlierSeverity(Map.of("energy", 900.0), 100);
        assertThat(result.worst()).isCloseTo(0.0523284151746532, within(EPS));
        assertThat(result.fields()).containsExactly("energy");

        // below the 0.8 * limit safe zone -> nothing
        assertThat(DishNutritionService.computeOutlierSeverity(Map.of("energy", 700.0), 100).worst()).isZero();

        // weight <= 0 short-circuits
        assertThat(DishNutritionService.computeOutlierSeverity(Map.of("energy", 9999.0), 0).worst()).isZero();

        // past 3x the limit -> NutritionValidationException (declared in
        // exceptions/ingredient.py, raised only from dish's pipeline)
        assertThatThrownBy(() -> DishNutritionService.computeOutlierSeverity(Map.of("energy", 2701.0), 100))
                .isInstanceOf(NutritionValidationException.class)
                .hasMessage("energy vượt ngưỡng vật lý")
                .satisfies(ex -> assertThat(((NutritionValidationException) ex).getField()).isEqualTo("energy"));
    }

    // =====================================================================
    // Confidence components
    // =====================================================================

    @Test
    void completenessScore_weightsCoreSeventyPercent() {
        Map<String, Double> coreOnly = Map.of("energy", 1.0, "protein", 1.0, "lipid", 1.0, "carbohydrate", 1.0);
        // 0.7 * (4/4) + 0.3 * (4/19)
        assertThat(service.computeCompletenessScore(coreOnly)).isCloseTo(0.763157894736842, within(1e-15));
        // nothing present -> floor of 0.2
        assertThat(service.computeCompletenessScore(Map.of())).isEqualTo(0.2);
    }

    @Test
    void dataQualityScore_appliesEverySeverityAndStatusPenalty() {
        Map<String, Double> none = Map.of();
        assertThat(service.computeDataQualityScore(none, IngredientImportStatus.APPROVED, IngredientSource.USDA))
                .isEqualTo(1.0);
        // ratio severity 0.5 -> 1 - 0.7*0.5
        assertThat(service.computeDataQualityScore(Map.of("ratio", 0.5),
                IngredientImportStatus.APPROVED, IngredientSource.USDA)).isCloseTo(0.65, within(EPS));
        assertThat(service.computeDataQualityScore(none, IngredientImportStatus.PENDING, IngredientSource.USDA))
                .isCloseTo(0.85, within(EPS));
        assertThat(service.computeDataQualityScore(none, IngredientImportStatus.REJECTED, IngredientSource.USDA))
                .isCloseTo(0.5, within(EPS));
        assertThat(service.computeDataQualityScore(none, IngredientImportStatus.APPROVED,
                IngredientSource.CHEF_SUGGESTION)).isCloseTo(0.9, within(EPS));
        // floor at 0.05
        assertThat(service.computeDataQualityScore(
                Map.of("ratio", 1.0, "macro", 1.0, "energy", 1.0, "outlier", 1.0, "semantic", 1.0),
                IngredientImportStatus.REJECTED, IngredientSource.CHEF_SUGGESTION)).isEqualTo(0.05);
    }

    // =====================================================================
    // Dish-level confidence
    // =====================================================================

    @Test
    void dishConfidence_matchesDjango_weightedAverageTimesQualityTimesCoverage() {
        List<DishIngredient> ingredients = List.of(
                DishIngredient.builder().weight(100.0).confidence(0.8).build(),
                DishIngredient.builder().weight(100.0).confidence(0.6).build());
        // weighted_avg 0.7 * quality_penalty (0.5 + 0.5*0.6 = 0.8) * coverage
        // (0.5 + 0.5*(1 - e^(-2/5))) = 0.37231038711002096 -> round(.., 3)
        assertThat(service.computeDishConfidence(ingredients)).isEqualTo(0.372);
    }

    @Test
    void dishConfidence_zeroWhenNoWeightedIngredients() {
        assertThat(service.computeDishConfidence(List.of())).isZero();
        assertThat(service.computeDishConfidence(List.of(
                DishIngredient.builder().weight(0.0).confidence(1.0).build()))).isZero();
    }

    // =====================================================================
    // Rounding / text helpers
    // =====================================================================

    @Test
    void pyRound_isHalfToEven_notHalfUp() {
        // The whole reason this helper exists: Math.round(0.5) would give 1.
        assertThat(DishNutritionService.pyRound(0.125, 2)).isEqualTo(0.12);
        assertThat(DishNutritionService.pyRound(0.135, 2)).isEqualTo(0.14);
        assertThat(DishNutritionService.pyRound(41.5, 0)).isEqualTo(42.0);
        assertThat(DishNutritionService.pyRound(42.5, 0)).isEqualTo(42.0);
    }

    @Test
    void pyRound_matchesPythonsExactBinaryRounding_notTheShortestDecimalString() {
        // Regression for a real port bug (PROGRESS.md): pyRound used to round
        // BigDecimal.valueOf(x) -- the shortest decimal STRING of the double -- instead of the
        // exact binary value, so it disagreed with Python's actual round() on these near-ties.
        // Verified against real Python (backend/venv): round(2.675, 2) == 2.67, round(0.0005, 3) == 0.001.
        assertThat(DishNutritionService.pyRound(2.675, 2)).isEqualTo(2.67);
        assertThat(DishNutritionService.pyRound(0.0005, 3)).isEqualTo(0.001);
    }

    @Test
    void smartRound_usesCustomerConfigByDefault_andChefConfigOnDemand() {
        assertThat(service.smartRound("energy", 41.64)).isEqualTo(42.0);        // customer: 0 decimals
        assertThat(service.smartRound("protein", 1.2345)).isEqualTo(1.2);       // customer: 1 decimal
        assertThat(service.smartRound("confidence", 0.85671)).isEqualTo(0.86);  // absent -> default 2
        assertThat(service.smartRound("energy", 41.6449, "chef")).isEqualTo(41.64); // chef: 2 decimals
        assertThat(service.smartRound("protein", 1.23456, "chef")).isEqualTo(1.235); // chef: 3 decimals
        assertThat(service.smartRound("confidence", 0.856712, "chef")).isEqualTo(0.8567);
        assertThat(service.smartRound("energy", null)).isZero();
    }

    @Test
    void confidenceTextAndNote_useDjangoThresholds() {
        assertThat(service.buildConfidenceText(0.8)).isEqualTo("Độ tin cậy cao (80.0%)");
        assertThat(service.buildConfidenceText(0.5)).isEqualTo("Độ tin cậy trung bình (50.0%)");
        assertThat(service.buildConfidenceText(0.49)).isEqualTo("Độ tin cậy thấp (49.0%)");

        assertThat(service.generateNote(0.8)).isEqualTo("Dữ liệu dinh dưỡng đáng tin cậy");
        assertThat(service.generateNote(0.5)).isEqualTo("Một số nguyên liệu chưa được xác thực hoàn toàn");
        assertThat(service.generateNote(0.49)).isEqualTo("Dữ liệu có thể không chính xác");
    }

    // =====================================================================
    // Full pipeline
    // =====================================================================

    private Ingredient usdaIngredient() {
        return Ingredient.builder()
                .name("gạo")
                .category(IngredientCategory.GRAIN)
                .weight(100.0)
                .energy(100.0)
                .protein(10.0)
                .lipid(0.0)
                .carbohydrate(15.0)
                .build();
    }

    private DishIngredientCreateRequest createRequest(double weight) {
        return new DishIngredientCreateRequest(null, weight, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void pipeline_withMatchingUsdaIngredient_scalesValuesAndProducesNoWarnings() {
        DishNutritionService.PipelineResult result = service.processIngredientPipeline(
                createRequest(100), usdaIngredient(),
                IngredientImportStatus.APPROVED, IngredientSource.USDA);

        assertThat(result.values()).containsEntry("energy", 100.0)
                .containsEntry("protein", 10.0)
                .containsEntry("lipid", 0.0)
                .containsEntry("carbohydrate", 15.0)
                .containsEntry("kali", null);
        assertThat(result.warnings()).isEmpty();
        // similarity 1.0 * completeness (0.7 + 0.3*4/19) * quality 1.0 -> round(.., 3)
        assertThat(result.confidence()).isEqualTo(0.763);
    }

    @Test
    void pipeline_doubleWeight_doublesEveryScaledValue() {
        DishNutritionService.PipelineResult result = service.processIngredientPipeline(
                createRequest(200), usdaIngredient(),
                IngredientImportStatus.APPROVED, IngredientSource.USDA);

        assertThat(result.values()).containsEntry("energy", 200.0)
                .containsEntry("protein", 20.0)
                .containsEntry("carbohydrate", 30.0);
        // Everything scales together, so all ratios stay 1.0 -> same confidence.
        assertThat(result.confidence()).isEqualTo(0.763);
    }

    @Test
    void pipeline_hardRatioOverride_raisesRatioHardWarning() {
        // 10x the USDA energy for the same weight -> ratio 10 > HARD_UPPER_BOUND (3.0)
        DishIngredientCreateRequest payload = new DishIngredientCreateRequest(
                null, 100, 1000.0, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        DishNutritionService.PipelineResult result = service.processIngredientPipeline(
                payload, usdaIngredient(), IngredientImportStatus.APPROVED, IngredientSource.USDA);

        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.type()).isEqualTo("ratio_hard");
            assertThat(w.field()).isEqualTo("energy");
            assertThat(w.severity()).isEqualTo(1.0);
            assertThat(w.message()).isEqualTo("energy sai nghiêm trọng (10.00x)");
        });
        assertThat(result.confidence()).isLessThan(0.763);
    }

    @Test
    void pipeline_zeroExpectedWithNonZeroOverride_raisesRatioZeroWarning() {
        // USDA lipid is 0 for this ingredient; the chef claims 12 g.
        DishIngredientCreateRequest payload = new DishIngredientCreateRequest(
                null, 100, null, null, 12.0, null, null, null, null,
                null, null, null, null, null, null, null, null);

        DishNutritionService.PipelineResult result = service.processIngredientPipeline(
                payload, usdaIngredient(), IngredientImportStatus.APPROVED, IngredientSource.USDA);

        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.type()).isEqualTo("ratio_zero");
            assertThat(w.field()).isEqualTo("lipid");
            assertThat(w.severity()).isEqualTo(1.0);
            assertThat(w.message()).isEqualTo("lipid sai nghiêm trọng (USDA=0 nhưng nhập 12.0)");
        });
    }

    @Test
    void pipeline_withoutUsdaIngredient_usesPayloadValues_addsInfoWarning_andConservativeConfidence() {
        DishIngredientSuggestionRequest payload = new DishIngredientSuggestionRequest(
                "bánh tráng nướng", IngredientCategory.GRAIN, null, 100,
                200.0, 10.0, 5.0, 20.0, null, null, null,
                null, null, null, null, null, null, null, null, 10);

        DishNutritionService.PipelineResult result = service.processIngredientPipeline(
                payload, null, IngredientImportStatus.PENDING, IngredientSource.CHEF_SUGGESTION);

        assertThat(result.values()).containsEntry("energy", 200.0).containsEntry("cholesterol", null);
        assertThat(result.warnings()).extracting(WarningResponse::type).contains("info");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w.message()).isEqualTo("Nguyên liệu chưa có trong USDA (AI estimate)"));
        // similarity 0.65 (no USDA entry) * completeness (0.7 + 0.3*4/19)
        //   * quality (macro 0.068041..., energy 0.192371..., CHEF_SUGGESTION, PENDING)
        // = 0.33836297021475265 -> round(.., 3)
        assertThat(result.confidence()).isEqualTo(0.338);
    }

    @Test
    void pipeline_runsLayer1WeightValidationBeforeAnythingElse() {
        // GRAIN bounds are (5, 1500) — 2 g is below the minimum, and the pipeline must
        // fail there rather than computing nutrition for junk data.
        assertThatThrownBy(() -> service.processIngredientPipeline(
                createRequest(2), usdaIngredient(), IngredientImportStatus.APPROVED, IngredientSource.USDA))
                .isInstanceOf(com.amomeal.marketplace.dish.exception.IngredientWeightOutOfBoundsException.class);
    }
}
