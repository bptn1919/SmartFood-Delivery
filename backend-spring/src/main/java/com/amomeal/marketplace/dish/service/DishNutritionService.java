package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.dto.NutritionPayload;
import com.amomeal.marketplace.dish.dto.WarningResponse;
import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.amomeal.marketplace.ingredient.exception.NutritionValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verbatim port of the nutrition compute / analyze / confidence pipeline that
 * lives inside ../../backend/dish/services/__init__.py::DishService
 * (the {@code _compute_*}, {@code _analyze_*}, {@code _ratio_*},
 * {@code _process_ingredient_pipeline}, {@code compute_dish_confidence},
 * {@code smart_round}, {@code build_response_*} block).
 *
 * <p>Split out of {@link DishService} purely for file size — every constant,
 * threshold, formula, comparison operator and warning string below is 1:1 with
 * the Python, including the quirks called out in inline PORT-NOTEs. Do not
 * "tidy" any of the numbers: they decide what the API rejects and what
 * confidence score a chef sees.
 *
 * <p>Two rounding helpers matter for parity: {@link #pyRound} reproduces
 * Python's {@code round()} (half-to-EVEN, not half-up like {@code Math.round}),
 * which is what produces the exact confidence/nutrition values the Django API
 * returns today. {@link #pyRound} delegates to {@link PyMath#round} — the exact
 * (binary-value, not shortest-decimal-string) implementation originally written
 * for {@code recommendation} and moved to {@code common.util} so both modules
 * share one Python-round implementation (PROGRESS.md: dish's own former
 * implementation rounded {@code BigDecimal.valueOf(x)}, the shortest decimal
 * STRING of the double, which diverges from Python's exact-binary-value
 * {@code round()} on near-ties, e.g. {@code round(2.675, 2)} is 2.67 in Python
 * but was 2.68 here).
 */
@Service
public class DishNutritionService {

    // ===== CONFIG (verbatim from DishService class attributes) =====
    static final double LOWER_BOUND = 0.5;
    static final double UPPER_BOUND = 2.0;

    static final double HARD_LOWER_BOUND = 0.3;
    static final double HARD_UPPER_BOUND = 3.0;

    static final double ENERGY_MACRO_TOLERANCE = 0.15;

    static final double RATIO_CONFIDENCE_PENALTY = 0.3;
    static final double MACRO_CONFIDENCE_PENALTY = 0.3;
    static final double INGREDIENT_COUNT_CONFIDENCE_PENALTY = 0.2;
    static final double SEMANTIC_CONFIDENCE_PENALTY = 0.1;
    static final double ENERGY_CONFIDENCE_PENALTY = 0.15;

    static final double PENDING_CUSTOM_INGREDIENT_PENALTY = 0.25;
    static final double REJECTED_CUSTOM_INGREDIENT_PENALTY = 0.45;
    static final double CHEF_SUGGESTION_SOURCE_PENALTY = 0.1;
    static final double ZERO_OVERRIDE_TOLERANCE = 0.5;

    /** Django: {@code self.CUSTOMER_ROUND_CONFIG}. */
    private static final Map<String, Integer> CUSTOMER_ROUND_CONFIG = Map.of(
            "weight", 1,
            "energy", 0,
            "protein", 1,
            "lipid", 1,
            "carbohydrate", 1,
            "fiber", 1,
            "natri", 1,
            "cholesterol", 0);

    /** Django: {@code self.CHEF_ROUND_CONFIG}. */
    private static final Map<String, Integer> CHEF_ROUND_CONFIG;

    static {
        Map<String, Integer> chef = new LinkedHashMap<>();
        chef.put("weight", 2);
        chef.put("energy", 2);
        chef.put("protein", 3);
        chef.put("lipid", 3);
        chef.put("carbohydrate", 3);
        chef.put("fiber", 3);
        chef.put("natri", 3);
        chef.put("kali", 3);
        chef.put("cholesterol", 2);
        // micronutrients (very small -> need high precision)
        chef.put("retinol", 4);
        chef.put("caroten", 4);
        chef.put("vitamin_b_1", 4);
        chef.put("vitamin_b_2", 4);
        chef.put("vitamin_pp", 4);
        chef.put("vitamin_c", 4);
        chef.put("calcium", 3);
        chef.put("phosphorus", 3);
        chef.put("fe", 3);
        chef.put("mg", 3);
        chef.put("zn", 3);
        chef.put("confidence", 4);
        CHEF_ROUND_CONFIG = Map.copyOf(chef);
    }

    /** Django: {@code LIMITS} inside {@code _compute_outlier_severity} (per 100 g). */
    private static final Map<String, Double> OUTLIER_LIMITS;

    static {
        Map<String, Double> limits = new LinkedHashMap<>();
        limits.put("energy", 900.0);         // kcal
        limits.put("protein", 60.0);         // g
        limits.put("lipid", 100.0);          // g
        limits.put("carbohydrate", 120.0);   // g
        limits.put("fiber", 50.0);           // g
        limits.put("natri", 3000.0);         // mg
        limits.put("kali", 5000.0);          // mg
        limits.put("cholesterol", 500.0);    // mg
        limits.put("retinol", 3000.0);       // ug
        limits.put("caroten", 15000.0);      // ug
        limits.put("vitamin_b_1", 10.0);     // mg
        limits.put("vitamin_b_2", 10.0);     // mg
        limits.put("vitamin_pp", 100.0);     // mg
        limits.put("vitamin_c", 1000.0);     // mg
        limits.put("calcium", 3000.0);       // mg
        limits.put("phosphorus", 2000.0);    // mg
        limits.put("fe", 30.0);              // mg
        limits.put("mg", 1000.0);            // mg
        limits.put("zn", 40.0);              // mg
        OUTLIER_LIMITS = Map.copyOf(limits);
    }

    private static final double OUTLIER_SAFE_RATIO = 0.8;
    private static final double OUTLIER_HARD_RATIO = 3.0;

    // =====================================================================
    // Small numeric helpers
    // =====================================================================

    /**
     * Python-compatible {@code round(value, digits)} — half-to-EVEN on the exact binary value,
     * unlike {@code Math.round}. Matters because every confidence/nutrition value the API returns
     * goes through it. Delegates to {@link PyMath#round} — see the class javadoc for why dish's
     * own former (shortest-decimal-string) implementation was wrong.
     */
    public static double pyRound(double value, int digits) {
        return PyMath.round(value, digits);
    }

    /** Django: {@code self._scaled_value(...)}. */
    static Double scaledValue(Double baseValue, Double referenceWeight, double weight) {
        if (baseValue == null) {
            return null;
        }
        double reference = (referenceWeight != null && referenceWeight > 0) ? referenceWeight : 100;
        return baseValue * (weight / reference);
    }

    /**
     * Django: {@code self._ratio_score(...)}.
     *
     * <p>PORT-NOTE: dead code in Django too — defined on DishService but never
     * called from anywhere (the analysis path uses {@link #ratioSeverity} and
     * {@link #computeSimilarityToUsdaEntry} instead). Ported for completeness so
     * a future change that starts using it does not have to re-derive it.
     */
    static double ratioScore(Double actual, double expected) {
        if (expected <= 0 || actual == null) {
            return 1.0;
        }
        double ratio = Math.max(actual, 1e-6) / Math.max(expected, 1e-6);
        double deviation = Math.abs(Math.log(ratio));
        return Math.exp(-1.5 * deviation);
    }

    /** Django: {@code self._ratio_severity(...)} — 0.0 = OK, 1.0 = badly wrong. */
    static double ratioSeverity(double actual, Double expected) {
        // CASE 1: expected = 0 (or missing)
        if (expected == null || expected == 0) {
            if (actual <= ZERO_OVERRIDE_TOLERANCE) {
                return 0.0;
            }
            return Math.min(1.0, Math.log10(actual + 1) / 4);
        }
        // CASE 2: normal
        double ratio = actual / Math.max(expected, 1e-6);
        double deviation = Math.abs(Math.log(Math.max(ratio, 1e-6)));
        return Math.min(1.0, deviation / 2.5);
    }

    /** Django: {@code self._compute_macro_severity(...)}. */
    static double computeMacroSeverity(double protein, double lipid, double carb, double weight) {
        if (weight <= 0) {
            return 0.0;
        }
        double total = protein + lipid + carb;
        double density = (total / weight) * 100; // per 100 g

        // HARD FAIL -> max severity instead of crashing
        if (density > 100 || density < 1) {
            return 1.0;
        }
        // SAFE ZONE
        if (density >= 5 && density <= 30) {
            return 0.0;
        }
        double x = density < 5 ? (5 - density) / 5 : (density - 30) / 30;
        return Math.min(1.0, Math.pow(x, 1.5));
    }

    /** Django: {@code self._compute_energy_severity(...)}. */
    static double computeEnergySeverity(double energy, double protein, double lipid, double carb) {
        double expected = 4 * protein + 9 * lipid + 4 * carb;
        if (expected <= 0) {
            return 0.0;
        }
        double ratio = energy / expected;
        if (ratio < 0.4 || ratio > 1.8) {
            return 1.0;
        }
        if (ratio >= 0.8 && ratio <= 1.2) {
            return 0.0;
        }
        return Math.min(1.0, Math.abs(Math.log(ratio)));
    }

    /** Result of {@code _compute_outlier_severity} (Python returns a tuple). */
    record OutlierSeverity(double worst, List<String> fields) {
    }

    /**
     * Django: {@code self._compute_outlier_severity(...)}.
     *
     * <p>Throws {@link NutritionValidationException} (declared in
     * exceptions/ingredient.py, raised only from here) when a per-100 g value
     * exceeds 3x its physical limit.
     */
    static OutlierSeverity computeOutlierSeverity(Map<String, Double> values, double weight) {
        if (weight <= 0) {
            return new OutlierSeverity(0.0, List.of());
        }
        double factor = 100 / weight;

        double worst = 0.0;
        List<String> fields = new ArrayList<>();

        for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
            Double v = values.get(field);
            if (v == null) {
                continue;
            }
            double per100 = v * factor;
            Double limit = OUTLIER_LIMITS.get(field);
            if (limit == null || limit == 0.0) {
                continue;
            }

            // HARD FAIL
            if (per100 > limit * OUTLIER_HARD_RATIO) {
                throw new NutritionValidationException(field + " vượt ngưỡng vật lý", field);
            }

            double safe = limit * OUTLIER_SAFE_RATIO;
            if (per100 <= safe) {
                continue;
            }

            // normalize [0 -> 1]
            double x = (per100 - safe) / (limit * OUTLIER_HARD_RATIO - safe);
            double score = Math.pow(x, 1.5) * (2 - x);

            worst = Math.max(worst, score);
            fields.add(field);
        }
        return new OutlierSeverity(worst, fields);
    }

    // =====================================================================
    // 1. COMPUTE (pure)
    // =====================================================================

    /** Django: {@code self._compute_nutrition(payload, ingredient)}. */
    Map<String, Double> computeNutrition(NutritionPayload payload, Ingredient ingredient) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
            Double base = NutrientFields.get(ingredient, field);
            Double expected = scaledValue(base, ingredient.getWeight(), payload.weight());
            Double override = payload.nutrient(field);
            values.put(field, override != null ? override : expected);
        }
        return values;
    }

    // =====================================================================
    // 2. ANALYZE (warnings + severity flags)
    // =====================================================================

    /** Result of the two {@code _analyze_nutrition_*} methods (Python returns a tuple). */
    record Analysis(List<WarningResponse> warnings, Map<String, Double> severity) {
    }

    /** Django: {@code self._analyze_nutrition_no_ingredient(payload, values)}. */
    Analysis analyzeNutritionNoIngredient(NutritionPayload payload, Map<String, Double> values) {
        List<WarningResponse> warnings = new ArrayList<>();
        Map<String, Double> severity = new LinkedHashMap<>();
        severity.put("macro", 0.0);
        severity.put("energy", 0.0);
        severity.put("outlier", 0.0);
        severity.put("semantic", 0.0);
        severity.put("completeness", 0.0);

        double protein = orZero(values.get("protein"));
        double lipid = orZero(values.get("lipid"));
        double carb = orZero(values.get("carbohydrate"));
        // Django: max(payload.weight or 1.0, 1.0) — note the 1.0 floor, which the
        // ratio-analysis path deliberately does NOT apply.
        double weight = Math.max(payload.weight() == 0 ? 1.0 : payload.weight(), 1.0);

        // MACRO
        double macro = computeMacroSeverity(protein, lipid, carb, weight);
        severity.put("macro", macro);
        if (macro > 0.35) {
            warnings.add(WarningResponse.of("macro", macro, "Tổng macro lệch so với trọng lượng"));
        }

        // ENERGY
        double energy = orZero(values.get("energy"));
        double energySeverity = computeEnergySeverity(energy, protein, lipid, carb);
        severity.put("energy", energySeverity);
        if (energySeverity > 0.35) {
            warnings.add(WarningResponse.of("energy", energySeverity, "Năng lượng không khớp macro"));
        }

        // OUTLIER — note: raw payload.weight(), NOT the floored `weight` above.
        OutlierSeverity outlier = computeOutlierSeverity(values, payload.weight());
        severity.put("outlier", outlier.worst());
        if (outlier.worst() > 0) {
            // PORT-NOTE: Django's dict also carries a "fields" key here; the ninja
            // WarningSchema drops it on serialization, so it is not exposed either.
            warnings.add(WarningResponse.of("outlier", outlier.worst(), "Dữ liệu dinh dưỡng bất thường"));
        }

        // SEMANTIC (light)
        if (lipid > weight * 0.8) {
            severity.put("semantic", 0.7);
            warnings.add(WarningResponse.of("semantic", 0.7, "Chất béo quá cao so với khối lượng"));
        }

        return new Analysis(warnings, severity);
    }

    /** Django: {@code self._analyze_nutrition_ratio(payload, ingredient, values)}. */
    Analysis analyzeNutritionRatio(NutritionPayload payload, Ingredient ingredient, Map<String, Double> values) {
        List<WarningResponse> warnings = new ArrayList<>();
        Map<String, Double> severity = new LinkedHashMap<>();
        severity.put("ratio", 0.0);
        severity.put("macro", 0.0);
        severity.put("energy", 0.0);

        double protein = orZero(values.get("protein"));
        double lipid = orZero(values.get("lipid"));
        double carb = orZero(values.get("carbohydrate"));

        // RATIO CHECK
        for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
            Double override = payload.nutrient(field);
            Double base = NutrientFields.get(ingredient, field);
            Double expected = scaledValue(base, ingredient.getWeight(), payload.weight());
            Double actualBoxed = override != null ? override : expected;

            if (expected == null) {
                continue;
            }
            double actual = actualBoxed;

            // CASE expected = 0
            if (expected == 0) {
                if (actual > ZERO_OVERRIDE_TOLERANCE) {
                    warnings.add(WarningResponse.of("ratio_zero", 1.0,
                            field + " sai nghiêm trọng (USDA=0 nhưng nhập " + pyFormat(actual) + ")", field));
                }
                continue;
            }

            // NORMAL CASE
            double ratio = actual / Math.max(expected, 1e-6);

            if (ratio < HARD_LOWER_BOUND || ratio > HARD_UPPER_BOUND) {
                warnings.add(WarningResponse.of("ratio_hard", 1.0,
                        field + " sai nghiêm trọng (" + String.format(Locale.ROOT, "%.2f", ratio) + "x)", field));
            }

            double running = Math.max(severity.get("ratio"), ratioSeverity(actual, expected));
            severity.put("ratio", running);

            // PORT-NOTE: this check sits INSIDE the loop and tests the running MAX,
            // so once any field pushes the ratio severity past 0.4, every subsequent
            // field in DISH_NUTRIENT_FIELDS also emits a "ratio" warning (with that
            // same running max as its severity), even if that field itself matches
            // perfectly. Faithfully preserved — it visibly changes the warnings array.
            if (running > 0.4) {
                warnings.add(WarningResponse.of("ratio", running, field + " lệch nhẹ", field));
            }
        }

        // MACRO — note: raw payload.weight(), no 1.0 floor on this path.
        double macro = computeMacroSeverity(protein, lipid, carb, payload.weight());
        severity.put("macro", macro);
        if (macro > 0.4) {
            warnings.add(WarningResponse.of("macro", macro, "Macro không phù hợp"));
        }

        // ENERGY
        double energy = orZero(values.get("energy"));
        double energySeverity = computeEnergySeverity(energy, protein, lipid, carb);
        severity.put("energy", energySeverity);
        if (energySeverity > 0.4) {
            warnings.add(WarningResponse.of("energy", energySeverity, "Energy không khớp macro"));
        }

        return new Analysis(warnings, severity);
    }

    // =====================================================================
    // 3. CONFIDENCE
    // =====================================================================

    /** Django: {@code self._compute_confidence(...)}. */
    double computeConfidence(NutritionPayload payload, Ingredient ingredient, Map<String, Double> values,
                             Map<String, Double> severity, IngredientImportStatus approvalStatus,
                             IngredientSource source) {
        double similarity = computeSimilarityToUsdaEntry(payload, ingredient, values);
        double completeness = computeCompletenessScore(values);
        double quality = computeDataQualityScore(severity, approvalStatus, source);

        double confidence = similarity * completeness * quality;
        return pyRound(Math.max(0.0, Math.min(confidence, 1.0)), 3);
    }

    /** Django: {@code self._compute_similarity_to_usda_entry(...)}. */
    double computeSimilarityToUsdaEntry(NutritionPayload payload, Ingredient ingredient, Map<String, Double> values) {
        // For a custom ingredient suggestion (no mapped USDA entry yet), keep a
        // conservative default.
        if (ingredient == null) {
            return 0.65;
        }

        List<Double> scores = new ArrayList<>();
        for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
            Double expected = scaledValue(NutrientFields.get(ingredient, field), ingredient.getWeight(), payload.weight());
            Double actual = values.get(field);

            if (expected == null || actual == null || expected <= 0) {
                continue;
            }
            double ratio = Math.max(actual, 1e-6) / Math.max(expected, 1e-6);
            // 1.0 when ratio=1, smoothly decreasing as the mismatch grows.
            double score = Math.exp(-Math.abs(Math.log(ratio)));
            scores.add(Math.max(0.0, Math.min(1.0, score)));
        }

        if (scores.isEmpty()) {
            return 0.75;
        }
        double mean = scores.stream().mapToDouble(Double::doubleValue).sum() / scores.size();
        return Math.max(0.35, Math.min(mean, 1.0));
    }

    /** Django: {@code self._compute_completeness_score(...)}. */
    double computeCompletenessScore(Map<String, Double> values) {
        List<String> coreFields = List.of("energy", "protein", "lipid", "carbohydrate");
        List<String> totalFields = NutrientFields.DISH_NUTRIENT_FIELDS;

        long coreAvailable = coreFields.stream().filter(f -> values.get(f) != null).count();
        double coreRatio = (double) coreAvailable / coreFields.size();

        long totalAvailable = totalFields.stream().filter(f -> values.get(f) != null).count();
        double fullRatio = (double) totalAvailable / totalFields.size();

        double score = 0.7 * coreRatio + 0.3 * fullRatio;
        return Math.max(0.2, Math.min(1.0, score));
    }

    /** Django: {@code self._compute_data_quality_score(...)}. */
    double computeDataQualityScore(Map<String, Double> severity, IngredientImportStatus approvalStatus,
                                   IngredientSource source) {
        double quality = 1.0;
        // CORE penalties. Missing keys read as 0 (Python .get(key, 0)) — which is why
        // the ratio path, whose severity map has no outlier/semantic keys, skips those.
        quality *= (1.0 - 0.7 * sev(severity, "ratio"));
        quality *= (1.0 - 0.5 * sev(severity, "macro"));
        quality *= (1.0 - 0.4 * sev(severity, "energy"));
        quality *= (1.0 - 0.3 * sev(severity, "outlier"));
        quality *= (1.0 - 0.2 * sev(severity, "semantic"));

        // SOURCE penalty
        if (source == IngredientSource.CHEF_SUGGESTION) {
            quality *= 0.9;
        }

        // APPROVAL penalty
        if (approvalStatus == IngredientImportStatus.PENDING) {
            quality *= 0.85;
        } else if (approvalStatus == IngredientImportStatus.REJECTED) {
            quality *= 0.5;
        }

        return Math.max(0.05, Math.min(quality, 1.0));
    }

    // =====================================================================
    // Pipeline entry point
    // =====================================================================

    /** Result of {@code _process_ingredient_pipeline} (Python returns a 3-tuple). */
    public record PipelineResult(Map<String, Double> values, List<WarningResponse> warnings, double confidence) {
    }

    /**
     * Django: {@code self._process_ingredient_pipeline(...)} — shared by
     * add_ingredient_to_dish, preview_ingredient_for_dish,
     * update_dish_ingredient and both suggest-ingredient endpoints, because all
     * of them need the compute/analyze/confidence triple to show the chef
     * warnings.
     *
     * <p>Layer 1 of the nutrition validation runs FIRST, before anything is
     * computed (see {@link NutritionValidation}).
     */
    public PipelineResult processIngredientPipeline(NutritionPayload payload, Ingredient ingredient,
                                                    IngredientImportStatus approvalStatus, IngredientSource source) {
        // Layer 1: weight sanity check before computing nutrition.
        NutritionValidation.validateIngredientWeight(
                payload.weight(),
                ingredient == null ? null : ingredient.getCategory(),
                ingredient != null ? ingredient.getName()
                        : (payload.customName() == null ? "" : payload.customName()));

        // 1. COMPUTE
        Map<String, Double> values;
        if (ingredient != null) {
            values = computeNutrition(payload, ingredient);
        } else {
            values = new LinkedHashMap<>();
            for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
                values.put(field, payload.nutrient(field));
            }
        }

        // 2. ANALYZE
        Analysis analysis;
        if (ingredient != null) {
            analysis = analyzeNutritionRatio(payload, ingredient, values);
        } else {
            analysis = analyzeNutritionNoIngredient(payload, values);
            analysis.warnings().add(WarningResponse.of("info", 0.3,
                    "Nguyên liệu chưa có trong USDA (AI estimate)", "ingredient"));
        }

        // 3. CONFIDENCE
        double confidence = computeConfidence(payload, ingredient, values, analysis.severity(), approvalStatus, source);

        return new PipelineResult(values, analysis.warnings(), confidence);
    }

    // =====================================================================
    // Dish-level aggregation
    // =====================================================================

    /** Django: {@code self.compute_dish_confidence(ingredients)}. */
    public double computeDishConfidence(List<DishIngredient> ingredients) {
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        List<Double> confidences = new ArrayList<>();

        for (DishIngredient di : ingredients) {
            double w = di.getWeight() == null ? 0.0 : di.getWeight();
            double c = di.getConfidence() == null ? 0.0 : di.getConfidence();
            if (w > 0) {
                totalWeight += w;
                weightedSum += w * c;
            }
            confidences.add(c);
        }

        if (totalWeight == 0) {
            return 0.0;
        }

        // BASE: weighted average
        double weightedAvg = weightedSum / totalWeight;

        // PENALTY 1: low-quality ingredient
        double minConf = confidences.isEmpty() ? 0.0 : confidences.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double qualityPenalty = 0.5 + 0.5 * minConf;

        // PENALTY 2: too few ingredients
        int n = ingredients.size();
        int k = 5; // enough ingredients to be trusted
        double coverage = 0.5 + 0.5 * (1 - Math.exp(-(double) n / k));

        return pyRound(weightedAvg * qualityPenalty * coverage, 3);
    }

    /** Django: {@code self.generate_note(conf)}. */
    public String generateNote(double conf) {
        if (conf >= 0.8) {
            return "Dữ liệu dinh dưỡng đáng tin cậy";
        } else if (conf >= 0.5) {
            return "Một số nguyên liệu chưa được xác thực hoàn toàn";
        }
        return "Dữ liệu có thể không chính xác";
    }

    /** Django: {@code self.build_confidence_text(conf)}. */
    public String buildConfidenceText(double conf) {
        double percent = pyRound(conf * 100, 1);
        String pct = trimTrailingZero(percent);
        if (conf >= 0.8) {
            return "Độ tin cậy cao (" + pct + "%)";
        } else if (conf >= 0.5) {
            return "Độ tin cậy trung bình (" + pct + "%)";
        }
        return "Độ tin cậy thấp (" + pct + "%)";
    }

    /** Django: {@code self.smart_round(field, value, mode)} — default mode is "customer". */
    public double smartRound(String field, Double value) {
        return smartRound(field, value, "customer");
    }

    public double smartRound(String field, Double value, String mode) {
        if (value == null) {
            return 0.0;
        }
        Map<String, Integer> config = "chef".equals(mode) ? CHEF_ROUND_CONFIG : CUSTOMER_ROUND_CONFIG;
        return pyRound(value, config.getOrDefault(field, 2));
    }

    /** Django: {@code self.compute_total_nutrition(ingredients)} (customer, 7 fields). */
    public Map<String, Double> computeTotalNutrition(List<DishIngredient> ingredients) {
        List<String> fields = List.of("energy", "protein", "lipid", "carbohydrate", "fiber", "natri", "cholesterol");
        Map<String, Double> totals = new LinkedHashMap<>();
        for (String field : fields) {
            double sum = 0.0;
            for (DishIngredient di : ingredients) {
                Double v = NutrientFields.get(di, field);
                sum += v == null ? 0.0 : v;
            }
            totals.put(field, smartRound(field, sum, "customer"));
        }
        return totals;
    }

    /** Django: {@code self.compute_total_nutrition_chef(ingredients)} (all 19 fields). */
    public Map<String, Double> computeTotalNutritionChef(List<DishIngredient> ingredients) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
            double sum = 0.0;
            for (DishIngredient di : ingredients) {
                Double v = NutrientFields.get(di, field);
                sum += v == null ? 0.0 : v;
            }
            // Django calls `r = self.smart_round` here (default mode), NOT the chef
            // lambda it uses for the per-ingredient rows — so these totals are rounded
            // with CUSTOMER_ROUND_CONFIG and fall back to 2 decimals for every field
            // that config does not list. Preserved verbatim.
            totals.put(field, smartRound(field, sum));
        }
        return totals;
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static double sev(Map<String, Double> severity, String key) {
        Double v = severity.get(key);
        return v == null ? 0.0 : v;
    }

    /** Renders a double the way Python renders it inside an f-string (41.0 -&gt; "41.0", 41.5 -&gt; "41.5"). */
    private static String pyFormat(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return Double.toString(value);
    }

    /** Python renders round(x, 1) as e.g. "82.5"; an integral result still shows ".0". */
    private static String trimTrailingZero(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return Double.toString(value);
    }
}
