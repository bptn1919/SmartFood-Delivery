package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.ingredient.service.SequenceMatcher;
import com.amomeal.marketplace.recommendation.entity.ActivityLevel;
import com.amomeal.marketplace.recommendation.entity.Gender;
import com.amomeal.marketplace.recommendation.entity.Goal;
import com.amomeal.marketplace.recommendation.entity.MealTime;
import com.amomeal.marketplace.recommendation.entity.UserDailyNutrition;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.amomeal.marketplace.common.util.PyMath.pyMax;
import static com.amomeal.marketplace.common.util.PyMath.pyMin;
import static com.amomeal.marketplace.common.util.PyMath.round;

/**
 * The pure (no DB, no HTTP) functions of ../../backend/recommendation/services/daily_nutrition.py
 * — BMR/TDEE/macro targets, the summary with uncertainty bands, the balanced-recommendation fit
 * scores, the meal-text parsers and the recipe math. Every function is pinned against the real
 * Python (fixture keys {@code targets}, {@code summary}, {@code nutrition_fit}, {@code heuristic},
 * {@code source_conf}, {@code json_safe}, {@code balanced_*}, ...).
 */
public final class DailyNutritionMath {

    public static final Map<String, Double> ACTIVITY_FACTOR = new LinkedHashMap<>();
    public static final Map<String, Double> PORTION_MULTIPLIER_HINTS = new LinkedHashMap<>();
    public static final double MAX_MEAL_RATIO = 0.60;
    public static final List<String> MACROS = List.of("protein_g", "lipid_g", "carb_g", "sodium_mg", "fiber_g");

    static {
        ACTIVITY_FACTOR.put("SEDENTARY", 1.2);
        ACTIVITY_FACTOR.put("LIGHT", 1.375);
        ACTIVITY_FACTOR.put("MODERATE", 1.55);
        ACTIVITY_FACTOR.put("ACTIVE", 1.725);
        ACTIVITY_FACTOR.put("VERY_ACTIVE", 1.9);
        PORTION_MULTIPLIER_HINTS.put("to cha ba", 1.8);
        PORTION_MULTIPLIER_HINTS.put("to lon", 1.5);
        PORTION_MULTIPLIER_HINTS.put("lon", 1.3);
        PORTION_MULTIPLIER_HINTS.put("nhieu", 1.3);
        PORTION_MULTIPLIER_HINTS.put("it", 0.8);
        PORTION_MULTIPLIER_HINTS.put("nho", 0.8);
    }

    private static final Set<String> STOP_TOKENS = Set.of(
            "sang", "trua", "chieu", "toi", "hom nay", "toi moi", "toi da", "an", "uong", "vua", "moi");
    private static final Pattern HEURISTIC_SPLIT = Pattern.compile(",|\\+|\\bva\\b|\\bkem\\b|\\bvoi\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DailyNutritionMath() {
    }

    /** One parsed meal ({@code ParsedMeal} dataclass). */
    public record ParsedMeal(String name, double quantityMultiplier, double confidenceParse) {
    }

    /** daily_nutrition's own {@code _clamp}: {@code max(0.0, min(1.0, float(value)))}. */
    public static double clamp(double value) {
        return pyMax(0.0, pyMin(1.0, value));
    }

    // =====================================================================
    // Profile normalisation + targets
    // =====================================================================

    public static Gender normalizeGender(String gender) {
        String v = (gender == null ? "" : gender).strip().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "MALE" -> Gender.MALE;
            case "FEMALE" -> Gender.FEMALE;
            default -> Gender.OTHER;
        };
    }

    public static ActivityLevel normalizeActivity(String activity) {
        String v = (activity == null ? "" : activity).strip().toUpperCase(Locale.ROOT);
        return ACTIVITY_FACTOR.containsKey(v) ? ActivityLevel.valueOf(v) : ActivityLevel.LIGHT;
    }

    public static Goal normalizeGoal(String goal) {
        String v = (goal == null ? "" : goal).strip().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "LOSE" -> Goal.LOSE;
            case "GAIN" -> Goal.GAIN;
            default -> Goal.MAINTAIN;
        };
    }

    /** Django {@code _compute_daily_targets} (Mifflin-St Jeor as written in the code, 3-dp rounding). */
    public static void computeDailyTargets(UserDailyNutrition daily) {
        double[] out = computeTargets(daily.getAge(), daily.getWeightKg(), daily.getHeightCm(),
                daily.getGender() == null ? null : daily.getGender().name(),
                daily.getActivityLevel() == null ? null : daily.getActivityLevel().name(),
                daily.getGoal() == null ? null : daily.getGoal().name());
        daily.setBmrKcal(out[0]);
        daily.setTdeeKcal(out[1]);
        daily.setTargetProteinG(out[2]);
        daily.setTargetLipidG(out[3]);
        daily.setTargetCarbG(out[4]);
        daily.setTargetSodiumMg(out[5]);
        daily.setTargetFiberG(out[6]);
    }

    /** [bmr, tdee, protein, lipid, carb, sodium, fiber], all rounded to 3 dp like Django. */
    public static double[] computeTargets(double ageIn, double weightIn, double heightIn, String gender,
                                          String activityLevel, String goal) {
        double age = Math.max(ageIn == 0 ? 25 : ageIn, 1.0);
        double weight = Math.max(weightIn == 0 ? 65 : weightIn, 1.0);
        double height = Math.max(heightIn == 0 ? 170 : heightIn, 1.0);
        double bmr;
        if ("MALE".equals(gender)) {
            bmr = 88.362 + (13.397 * weight) + (4.799 * height) - (5.677 * age);
        } else if ("FEMALE".equals(gender)) {
            bmr = 447.593 + (9.247 * weight) + (3.098 * height) - (4.33 * age);
        } else {
            double bmrMale = 88.362 + (13.397 * weight) + (4.799 * height) - (5.677 * age);
            double bmrFemale = 447.593 + (9.247 * weight) + (3.098 * height) - (4.33 * age);
            bmr = (bmrMale + bmrFemale) / 2.0;
        }
        double tdee = bmr * ACTIVITY_FACTOR.getOrDefault(activityLevel, 1.375);
        if ("LOSE".equals(goal)) {
            tdee -= 300;
        } else if ("GAIN".equals(goal)) {
            tdee += 300;
        }
        tdee = Math.max(tdee, 900.0);
        double protein = "GAIN".equals(goal) ? 1.2 * weight : 1.0 * weight;
        double lipid = (0.27 * tdee) / 9.0;
        double carb = Math.max((tdee - (protein * 4.0 + lipid * 9.0)) / 4.0, 0.0);
        double sodium = 2300.0;
        double fiber = (14.0 * tdee) / 1000.0;
        return new double[]{round(bmr, 3), round(tdee, 3), round(protein, 3), round(lipid, 3), round(carb, 3),
                round(sodium, 3), round(fiber, 3)};
    }

    /** Django {@code _infer_meal_time_from_delivery_time}. */
    public static MealTime inferMealTime(LocalTime deliveryTime) {
        if (deliveryTime == null) {
            return MealTime.UNKNOWN;
        }
        int hour = deliveryTime.getHour();
        if (hour >= 6 && hour < 11) {
            return MealTime.BREAKFAST;
        } else if (hour >= 11 && hour < 16) {
            return MealTime.LUNCH;
        } else if (hour >= 16 && hour < 22) {
            return MealTime.DINNER;
        } else {
            return MealTime.SNACK;
        }
    }

    // =====================================================================
    // Summary
    // =====================================================================

    /** One active meal log's values used by {@code _compute_consumed_uncertainty}. */
    public record LogValues(double protein, double lipid, double carb, double sodium, double fiber,
                            double confidenceParse, double confidenceSource) {
    }

    private static double orOne(double v) {
        return v == 0.0 ? 1.0 : v;
    }

    /** Django {@code _compute_consumed_uncertainty}: Σ nutrition × (1 − conf_parse × conf_source), 3 dp. */
    public static Map<String, Double> consumedUncertainty(List<LogValues> logs) {
        double[] u = new double[5];
        for (LogValues log : logs) {
            // float(x or 1.0): a stored 0.0 confidence counts as 1.0 (faithful quirk).
            double alpha = 1.0 - clamp(orOne(log.confidenceParse())) * clamp(orOne(log.confidenceSource()));
            u[0] += log.protein() * alpha;
            u[1] += log.lipid() * alpha;
            u[2] += log.carb() * alpha;
            u[3] += log.sodium() * alpha;
            u[4] += log.fiber() * alpha;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            out.put(MACROS.get(i), round(u[i], 3));
        }
        return out;
    }

    /** Django {@code _build_summary}'s dict. */
    public record Summary(String date, double bmrKcal, double tdeeKcal, Map<String, Double> target,
                          Map<String, Double> consumed, Map<String, Double> remaining) {
    }

    public static Summary buildSummary(UserDailyNutrition daily, Map<String, Double> uncertainty) {
        Map<String, Double> target = new LinkedHashMap<>();
        target.put("protein_g", daily.getTargetProteinG());
        target.put("lipid_g", daily.getTargetLipidG());
        target.put("carb_g", daily.getTargetCarbG());
        target.put("sodium_mg", daily.getTargetSodiumMg());
        target.put("fiber_g", daily.getTargetFiberG());
        Map<String, Double> consumedRaw = new LinkedHashMap<>();
        consumedRaw.put("protein_g", daily.getConsumedProteinG());
        consumedRaw.put("lipid_g", daily.getConsumedLipidG());
        consumedRaw.put("carb_g", daily.getConsumedCarbG());
        consumedRaw.put("sodium_mg", daily.getConsumedSodiumMg());
        consumedRaw.put("fiber_g", daily.getConsumedFiberG());

        Map<String, Double> consumed = new LinkedHashMap<>();
        Map<String, Double> remaining = new LinkedHashMap<>();
        for (String k : MACROS) {
            consumed.put(k, round(consumedRaw.get(k), 3));
            consumed.put(k + "_uncertainty", uncertainty.get(k));
        }
        for (String k : MACROS) {
            double mid = round(target.get(k) - consumedRaw.get(k), 3);
            double unc = uncertainty.get(k);
            remaining.put(k, mid);
            remaining.put(k + "_lower", round(mid - unc, 3));
            remaining.put(k + "_upper", round(mid + unc, 3));
        }
        Map<String, Double> targetOut = new LinkedHashMap<>();
        target.forEach((k, v) -> targetOut.put(k, round(v, 3)));
        return new Summary(String.valueOf(daily.getDate()), round(daily.getBmrKcal(), 3),
                round(daily.getTdeeKcal(), 3), targetOut, consumed, remaining);
    }

    // =====================================================================
    // Balanced recommendation fit
    // =====================================================================

    private static double get(Map<String, Double> m, String key, double dflt) {
        Double v = m.get(key);
        return v == null ? dflt : v;
    }

    /** Django {@code _macro_match_score} (range-aware, protein 0.4 / lipid 0.2 / carb 0.4). */
    public static double macroMatchScore(Map<String, Double> remaining, Map<String, Double> dishNutrition) {
        String[] keys = {"protein_g", "lipid_g", "carb_g"};
        double[] weights = {0.4, 0.2, 0.4};
        double score = 0.0;
        double totalWeight = 0.0;
        for (int i = 0; i < 3; i++) {
            String k = keys[i];
            double w = weights[i];
            double remMid = pyMax(get(remaining, k, 0.0), 0.0);
            double remLower = pyMax(get(remaining, k + "_lower", remMid), 0.0);
            double remUpper = pyMax(get(remaining, k + "_upper", remMid), 0.0);
            double val = pyMax(get(dishNutrition, k, 0.0), 0.0);
            if (remMid <= 0) {
                continue;
            }
            double sub;
            if (val < remLower) {
                sub = val / remMid;
            } else if (val <= remUpper) {
                sub = 1.0;
            } else {
                double overflowRatio = (val - remUpper) / pyMax(remUpper, 1.0);
                sub = pyMax(0.0, 1.0 - overflowRatio * 2.0);
            }
            score += sub * w;
            totalWeight += w;
        }
        return totalWeight > 0 ? score / totalWeight : 0.5;
    }

    public static double sodiumPenalty(Map<String, Double> remaining, Map<String, Double> dishNutrition) {
        double remainingSodium = get(remaining, "sodium_mg", 0.0);
        double dishSodium = pyMax(get(dishNutrition, "sodium_mg", 0.0), 0.0);
        if (remainingSodium > 400) {
            return 0.0;
        }
        return pyMax(0.0, pyMin(1.0, dishSodium / 2000.0));
    }

    /** Django {@code _suggest_servings}: median of remaining/dish over protein/lipid/carb, in [0.5, 2.5]. */
    public static double suggestServings(Map<String, Double> remaining, Map<String, Double> dishNutrition) {
        List<Double> candidates = new ArrayList<>();
        for (String key : List.of("protein_g", "lipid_g", "carb_g")) {
            double rem = pyMax(get(remaining, key, 0.0), 0.0);
            double val = get(dishNutrition, key, 0.0);
            if (rem > 0 && val > 0) {
                candidates.add(rem / val);
            }
        }
        if (candidates.isEmpty()) {
            return 1.0;
        }
        candidates.sort(Double::compare);
        double median = candidates.get(candidates.size() / 2);
        return pyMax(0.5, pyMin(2.5, median));
    }

    /** Django {@code _validate_meal_portion}: no single meal above 60% of any daily target (floor 0.5). */
    public static double validateMealPortion(double servings, Map<String, Double> dishNutrition,
                                             Map<String, Double> dailyTarget) {
        double maxServings = Double.POSITIVE_INFINITY;
        for (String k : MACROS) {
            double dishValue = get(dishNutrition, k, 0.0);
            double targetValue = get(dailyTarget, k, 0.0);
            if (dishValue > 0 && targetValue > 0) {
                maxServings = pyMin(maxServings, (MAX_MEAL_RATIO * targetValue) / dishValue);
            }
        }
        if (maxServings == Double.POSITIVE_INFINITY) {
            return servings;
        }
        if (servings <= maxServings) {
            return servings;
        }
        return pyMax(0.5, maxServings);
    }

    public static List<String> remainingReasons(Map<String, Double> remaining) {
        List<String> reasons = new ArrayList<>();
        if (get(remaining, "protein_g", 0.0) > 10) {
            reasons.add("Cần bổ sung protein trong ngày");
        }
        if (get(remaining, "carb_g", 0.0) > 20) {
            reasons.add("Cần bổ sung carbohydrate cho mục tiêu năng lượng");
        }
        if (get(remaining, "lipid_g", 0.0) > 8) {
            reasons.add("Cần bổ sung chất béo lành mạnh");
        }
        if (get(remaining, "fiber_g", 0.0) > 5) {
            reasons.add("Cần tăng chất xơ để cân bằng bữa ăn");
        }
        if (reasons.isEmpty()) {
            reasons.add("Phù hợp cân bằng dinh dưỡng hiện tại");
        }
        return reasons;
    }

    /** The ranked item dict of {@code get_balanced_recommendations} (before the response schema). */
    public record BalancedItem(int rankPosition, String dishUid, String dishName, String publicUrl, double price,
                               double avgRating, double baseRecommendationScore, double macroMatchScore,
                               double finalScore, double suggestedServings, Map<String, Double> nutritionImpact,
                               List<String> reasons) {
    }

    /** One pipeline feed item as consumed by the balanced ranking. */
    public record FeedItem(String dishUid, String dishName, String publicUrl, double price, double avgRating,
                           double score, List<String> reasons) {
    }

    /**
     * The ranking loop of {@code get_balanced_recommendations} after the pipeline call: filter
     * already-logged dishes (fallback to unfiltered below max(5, limit)), score each item for
     * explanation only (MMR order is preserved), suggest + cap servings. Pinned against Python.
     */
    public static List<BalancedItem> rankBalanced(List<FeedItem> baseItems, Set<String> alreadyLogged, int limit,
                                                  Summary summary,
                                                  Function<List<String>, Map<String, Map<String, Double>>> nutritionMap,
                                                  Function<List<String>, Map<String, Double>> qualityMap) {
        List<FeedItem> filtered = new ArrayList<>();
        for (FeedItem item : baseItems) {
            if (!alreadyLogged.contains(item.dishUid())) {
                filtered.add(item);
            }
        }
        if (filtered.size() < Math.max(5, limit)) {
            filtered = baseItems;
        }
        List<String> dishIds = filtered.stream().map(FeedItem::dishUid).toList();
        Map<String, Map<String, Double>> nutrition = nutritionMap.apply(dishIds);
        Map<String, Double> quality = qualityMap.apply(dishIds);

        List<Double> scores = filtered.stream().map(FeedItem::score).toList();
        double maxBase = PyMath.pyMax(scores);
        if (maxBase == 0.0) {
            maxBase = 1.0; // Python `max(...) or 1.0`
        }
        Map<String, Double> remaining = summary.remaining();
        List<BalancedItem> ranked = new ArrayList<>();
        for (int idx = 0; idx < filtered.size(); idx++) {
            FeedItem item = filtered.get(idx);
            Map<String, Double> dishNutrition = nutrition.getOrDefault(item.dishUid(), emptyNutrition());
            double macroMatch = macroMatchScore(remaining, dishNutrition);
            double sodium = sodiumPenalty(remaining, dishNutrition);
            double baseNorm = pyMax(0.0, pyMin(1.0, item.score() / maxBase));
            double finalScore = 0.55 * macroMatch + 0.30 * baseNorm - 0.15 * sodium;
            finalScore = pyMax(0.0, pyMin(1.0, finalScore));

            double servings = suggestServings(remaining, dishNutrition);
            double dishAlpha = 1.0 - clamp(quality.getOrDefault(item.dishUid(), 0.98));
            Map<String, Double> impact = impact(dishNutrition, servings, dishAlpha);
            double adjusted = validateMealPortion(servings, dishNutrition, summary.target());
            List<String> reasons = new ArrayList<>(item.reasons().subList(0, Math.min(2, item.reasons().size())));
            if (adjusted != servings) {
                impact = impact(dishNutrition, adjusted, dishAlpha);
                reasons.add("⚠️ Portion adjusted to fit daily targets (max " + (int) (MAX_MEAL_RATIO * 100) + "% per meal)");
                servings = adjusted;
            }
            List<String> deficit = remainingReasons(remaining);
            reasons.addAll(deficit.subList(0, Math.min(deficit.size(), Math.max(0, 3 - reasons.size()))));
            ranked.add(new BalancedItem(idx + 1, item.dishUid(), item.dishName(), item.publicUrl(), item.price(),
                    item.avgRating(), round(item.score(), 6), round(macroMatch, 6), round(finalScore, 6),
                    round(servings, 2), impact, new ArrayList<>(reasons.subList(0, Math.min(3, reasons.size())))));
        }
        return new ArrayList<>(ranked.subList(0, Math.min(Math.max(limit, 1), ranked.size())));
    }

    private static Map<String, Double> impact(Map<String, Double> n, double srv, double alpha) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String k : MACROS) {
            double v = get(n, k, 0.0);
            out.put(k, round(v * srv, 3));
            out.put(k + "_uncertainty", round(v * srv * alpha, 3));
        }
        return out;
    }

    public static Map<String, Double> emptyNutrition() {
        Map<String, Double> m = new LinkedHashMap<>();
        MACROS.forEach(k -> m.put(k, 0.0));
        return m;
    }

    // =====================================================================
    // Dish nutrition / quality maps
    // =====================================================================

    /** One {@code DishIngredient.values("dish_id","protein",...,"dish__serving_size","weight","confidence")} row. */
    public record DishIngredientRow(String dishId, Double protein, Double lipid, Double carbohydrate, Double natri,
                                    Double fiber, Integer servingSize, Double weight, Double confidence) {
    }

    private static double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    /** Django {@code _get_dish_nutrition_map}: per-SERVING totals (each row divided by max(serving_size, 1)). */
    public static Map<String, Map<String, Double>> dishNutritionMap(List<DishIngredientRow> rows) {
        Map<String, Map<String, Double>> map = new LinkedHashMap<>();
        for (DishIngredientRow row : rows) {
            Map<String, Double> m = map.computeIfAbsent(row.dishId(), k -> emptyNutrition());
            int serving = Math.max((row.servingSize() == null || row.servingSize() == 0) ? 1 : row.servingSize(), 1);
            m.merge("protein_g", orZero(row.protein()) / serving, Double::sum);
            m.merge("lipid_g", orZero(row.lipid()) / serving, Double::sum);
            m.merge("carb_g", orZero(row.carbohydrate()) / serving, Double::sum);
            m.merge("sodium_mg", orZero(row.natri()) / serving, Double::sum);
            m.merge("fiber_g", orZero(row.fiber()) / serving, Double::sum);
        }
        return map;
    }

    /** Django {@code _get_dish_quality_map}: weight-weighted ingredient confidence (0.9 when no weight). */
    public static Map<String, Double> dishQualityMap(List<DishIngredientRow> rows, List<String> dishIds) {
        Map<String, Double> weighted = new LinkedHashMap<>();
        Map<String, Double> totals = new LinkedHashMap<>();
        for (DishIngredientRow row : rows) {
            double weight = pyMax((row.weight() == null || row.weight() == 0.0) ? 1.0 : row.weight(), 0.0);
            double confidence = clamp((row.confidence() == null || row.confidence() == 0.0) ? 1.0 : row.confidence());
            weighted.merge(row.dishId(), weight * confidence, Double::sum);
            totals.merge(row.dishId(), weight, Double::sum);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String dishId : dishIds) {
            double total = totals.getOrDefault(dishId, 0.0);
            if (total <= 0) {
                result.put(dishId, 0.9);
                continue;
            }
            result.put(dishId, clamp(weighted.getOrDefault(dishId, 0.0) / total));
        }
        return result;
    }

    // =====================================================================
    // Source confidence
    // =====================================================================

    public static double nameSimilarity(String rawName, String resolvedName) {
        String a = RemoveAccents.apply((rawName == null ? "" : rawName).strip().toLowerCase(Locale.ROOT));
        String b = RemoveAccents.apply((resolvedName == null ? "" : resolvedName).strip().toLowerCase(Locale.ROOT));
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        double tokenOverlap = 0.0;
        Set<String> ta = pySplit(a);
        Set<String> tb = pySplit(b);
        if (!ta.isEmpty() && !tb.isEmpty()) {
            Set<String> inter = new HashSet<>(ta);
            inter.retainAll(tb);
            Set<String> union = new HashSet<>(ta);
            union.addAll(tb);
            tokenOverlap = (double) inter.size() / union.size();
        }
        double seqRatio = SequenceMatcher.ratio(a, b);
        return clamp((0.55 * tokenOverlap) + (0.45 * seqRatio));
    }

    private static Set<String> pySplit(String s) {
        Set<String> out = new HashSet<>();
        for (String t : WHITESPACE.split(s.strip())) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public static double nutritionCompleteness(Map<String, Double> nutrition) {
        int valid = 0;
        for (String k : MACROS) {
            Double v = nutrition.get(k);
            if (v == null) {
                continue;
            }
            if (v > 0) {
                valid++;
            }
        }
        double ratio = valid / (double) MACROS.size();
        return pyMax(0.2, clamp(ratio));
    }

    public static double computeSourceConfidence(String parsedName, String resolvedName, Map<String, Double> nutrition,
                                                 Double baseQuality) {
        double similarity = nameSimilarity(parsedName, resolvedName);
        double completeness = nutritionCompleteness(nutrition);
        double quality = clamp(baseQuality == null ? 0.0 : baseQuality);
        double confidence = similarity * completeness * quality;
        return pyMax(0.2, round(clamp(confidence), 3));
    }

    // =====================================================================
    // Meal text parsing
    // =====================================================================

    /** Django {@code _heuristic_parse}: split on , + "va" "kem" "voi", portion hints, stop tokens. */
    public static List<ParsedMeal> heuristicParse(String text) {
        String normalized = RemoveAccents.apply((text == null ? "" : text).toLowerCase(Locale.ROOT));
        List<ParsedMeal> results = new ArrayList<>();
        for (String raw : HEURISTIC_SPLIT.split(normalized, -1)) {
            String segment = WHITESPACE.matcher(raw).replaceAll(" ").strip();
            if (segment.isEmpty()) {
                continue;
            }
            double quantityMultiplier = 1.0;
            for (Map.Entry<String, Double> hint : PORTION_MULTIPLIER_HINTS.entrySet()) {
                if (segment.contains(hint.getKey())) {
                    quantityMultiplier = pyMax(quantityMultiplier, hint.getValue());
                }
            }
            List<String> tokens = new ArrayList<>();
            for (String token : segment.split(" ", -1)) {
                if (!token.isEmpty() && !STOP_TOKENS.contains(token)) {
                    tokens.add(token);
                }
            }
            String mealName = String.join(" ", tokens).strip();
            if (mealName.codePointCount(0, mealName.length()) < 2) {
                continue;
            }
            results.add(new ParsedMeal(mealName, pyMax(0.2, pyMin(3.0, quantityMultiplier)), 0.65));
        }
        return results;
    }

    /** Python {@code float(x)} for a JSON value (numbers, numeric strings); throws like Python on garbage. */
    static double pyFloat(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            String t = s.strip().replace("_", "");
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.equals("inf") || lower.equals("+inf") || lower.equals("infinity") || lower.equals("+infinity")) {
                return Double.POSITIVE_INFINITY;
            }
            if (lower.equals("-inf") || lower.equals("-infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            if (lower.equals("nan") || lower.equals("+nan") || lower.equals("-nan")) {
                return Double.NaN;
            }
            if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
                throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
            }
            return Double.parseDouble(t);
        }
        throw new IllegalArgumentException("float() argument must be a string or a real number");
    }

    /** Python truthiness of a JSON value. */
    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** Python {@code x or y}. */
    static Object or(Object x, Object y) {
        return truthy(x) ? x : y;
    }

    /** Python {@code str(x)} for JSON scalars. */
    static String pyStr(Object v) {
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    /** Iterating a JSON value the way Python's {@code for row in rows} would (dict → keys; str → chars). */
    static List<Object> pyIter(Object rows) {
        if (rows instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (rows instanceof Map<?, ?> m) {
            return new ArrayList<>(m.keySet());
        }
        if (rows instanceof String s) {
            List<Object> chars = new ArrayList<>();
            s.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            return chars;
        }
        throw new IllegalArgumentException("object is not iterable");
    }

    /** Django {@code _normalize_parsed_rows}. Throws where Python would (non-iterable rows, bad floats). */
    public static List<ParsedMeal> normalizeParsedRows(Object rows) {
        List<ParsedMeal> results = new ArrayList<>();
        for (Object row : pyIter(rows)) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String name = pyStr(or(map.get("name"), "")).strip();
            if (name.isEmpty()) {
                continue;
            }
            double quantityMultiplier = pyFloat(or(map.get("quantity_multiplier"), 1.0));
            double confidenceParse = pyFloat(or(or(map.get("confidence_parse"), map.get("confidence")), 0.7));
            results.add(new ParsedMeal(name, pyMax(0.2, pyMin(3.0, quantityMultiplier)), clamp(confidenceParse)));
        }
        return results;
    }

    /** Django {@code _extract_json}: {@code re.search(r"\{.*\}", text, re.S)} (greedy) or the text itself. */
    public static String extractJson(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /** Django {@code _parse_json_safely}: json.loads, else the outermost {...} slice, else null. */
    public static Object parseJsonSafely(String content) {
        String text = (content == null ? "" : content).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (RuntimeException ignored) {
            // fall through
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            return null;
        }
        try {
            return JSON.readValue(text.substring(start, end + 1), Object.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // =====================================================================
    // Gemini recipe math
    // =====================================================================

    /** {@code {"name", "ingredient_uid", "weight_g"}} entry of a mapped recipe. */
    public record MappedIngredient(String name, String ingredientUid, double weightG) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("ingredient_uid", ingredientUid);
            m.put("weight_g", weightG);
            return m;
        }
    }

    public record MappedRecipe(List<MappedIngredient> ingredients, double confidence) {
    }

    /** Django {@code _normalize_and_map_recipe}; {@code matcher} = {@code _match_usda_ingredient} (name → uid or null). */
    public static MappedRecipe normalizeAndMapRecipe(Map<?, ?> parsed, Function<String, String> matcher) {
        List<MappedIngredient> results = new ArrayList<>();
        Object rawIngs = parsed.get("ingredients");
        double confidence = pyFloat(or(parsed.get("confidence"), 0.0));
        for (Object item : truthy(rawIngs) ? pyIter(rawIngs) : List.of()) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String name = pyStr(or(m.get("name"), "")).strip();
            double weight = pyFloat(or(or(m.get("weight_g"), m.get("weight")), 0.0));
            if (name.isEmpty() || weight <= 0) {
                continue;
            }
            results.add(new MappedIngredient(name, matcher.apply(name), round(weight, 3)));
        }
        return new MappedRecipe(results, clamp(confidence));
    }

    /** One {@code Ingredient} row's per-100 g values. */
    public record IngredientNutrition(Double protein, Double lipid, Double carbohydrate, Double natri, Double fiber) {
    }

    /** Django {@code _compute_recipe_nutrition}: Σ ingredient(per 100 g) × weight/100, 3 dp; unknown uids skipped. */
    public static Map<String, Double> computeRecipeNutrition(List<MappedIngredient> mapped,
                                                             Map<String, IngredientNutrition> ingredients) {
        double[] t = new double[5];
        for (MappedIngredient item : mapped) {
            String uid = item.ingredientUid();
            if (uid == null || !ingredients.containsKey(uid)) {
                continue;
            }
            IngredientNutrition ing = ingredients.get(uid);
            double factor = item.weightG() / 100.0;
            t[0] += orZero(ing.protein()) * factor;
            t[1] += orZero(ing.lipid()) * factor;
            t[2] += orZero(ing.carbohydrate()) * factor;
            t[3] += orZero(ing.natri()) * factor;
            t[4] += orZero(ing.fiber()) * factor;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            out.put(MACROS.get(i), round(t[i], 3));
        }
        return out;
    }
}
