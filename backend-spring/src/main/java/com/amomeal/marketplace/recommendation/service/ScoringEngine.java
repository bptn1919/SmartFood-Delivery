package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.entity.Dish;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.amomeal.marketplace.common.util.PyMath.clamp;
import static com.amomeal.marketplace.common.util.PyMath.round;

/**
 * 1:1 port of ../../backend/recommendation/services/scoring.py::ScoringEngine.
 *
 * <pre>
 * final_score = w1·base + w2·favorite + w3·history + diet_component
 *               − (w4+w5)·final_penalty − strong_gate_penalty
 * </pre>
 *
 * Every formula, clamp, default and rounding (6 decimals) is transcribed literally and pinned
 * against the real Python (fixture keys {@code score_*}, {@code diet_alignment}, {@code ema},
 * {@code cosine}, ... in {@code python_vectors.json}). Plain class (not a bean), constructed per
 * request by the pipeline exactly like Django.
 *
 * <p>PORT-NOTE (faithful quirks, all reproduced):
 * <ul>
 *   <li>{@code float(row.get("quantity") or 1)} — a quantity of 0 counts as 1.</li>
 *   <li>The scoring-time dish vector uses the raw {@code natri} in mg and no confidence weighting,
 *       while the persisted vectors of {@link VectorIndexService} use sodium in grams and
 *       weight×confidence; the EMA then mixes the two scales. Django does exactly this.</li>
 *   <li>The fallback vector mean iterates a Python {@code set} (hash order); Java iterates the
 *       candidate order — identical up to float summation order (last-ulp only).</li>
 * </ul>
 */
public class ScoringEngine {

    private final double timeDecayLambda;
    private final double recencyDecayLambda;
    private final Map<String, Double> weights;
    private final double penaltyGamma;
    private final double penaltyCorrelationThreshold;
    private final double favoriteRecencyDecayLambda;
    private final double userVectorEmaAlpha;
    private final boolean prioritizeUnorderedDishes;
    private final double unifiedPenaltyWeight;
    private final double[] balancedTarget;
    private final Clock clock;

    public ScoringEngine(double timeDecayLambda, double recencyDecayLambda, Map<String, Double> weights,
                         double penaltyGamma, double penaltyCorrelationThreshold, List<Double> balancedTarget,
                         Double favoriteRecencyDecayLambda, double userVectorEmaAlpha,
                         boolean prioritizeUnorderedDishes, Clock clock) {
        this.timeDecayLambda = timeDecayLambda;
        this.recencyDecayLambda = recencyDecayLambda;
        this.weights = weights;
        this.penaltyGamma = penaltyGamma;
        this.penaltyCorrelationThreshold = penaltyCorrelationThreshold;
        this.favoriteRecencyDecayLambda = favoriteRecencyDecayLambda != null ? favoriteRecencyDecayLambda : timeDecayLambda;
        this.userVectorEmaAlpha = clamp(userVectorEmaAlpha);
        this.prioritizeUnorderedDishes = prioritizeUnorderedDishes;
        // float(self.weights.get("w_penalty", self.weights.get("w4", 0.0) + self.weights.get("w5", 0.0)))
        this.unifiedPenaltyWeight = weights.containsKey("w_penalty")
                ? weights.get("w_penalty")
                : weights.getOrDefault("w4", 0.0) + weights.getOrDefault("w5", 0.0);
        this.clock = clock;

        double[] target = {0.3, 0.3, 0.4};
        if (balancedTarget != null && balancedTarget.size() == 3) {
            double p = Math.max(balancedTarget.get(0), 0.0);
            double f = Math.max(balancedTarget.get(1), 0.0);
            double c = Math.max(balancedTarget.get(2), 0.0);
            double sum = p + f + c;
            if (sum > 0) {
                target = new double[]{p / sum, f / sum, c / sum};
            }
        }
        this.balancedTarget = target;
    }

    public double[] balancedTarget() {
        return balancedTarget.clone();
    }

    public double unifiedPenaltyWeight() {
        return unifiedPenaltyWeight;
    }

    // =====================================================================
    // Pure helpers (static where Django's are @staticmethod)
    // =====================================================================

    /** Django {@code _cosine_similarity}: zip() truncates the dot product, norms use full vectors. */
    public static double cosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.isEmpty() || v2.isEmpty()) {
            return 0.0;
        }
        double dot = 0;
        int n = Math.min(v1.size(), v2.size());
        for (int i = 0; i < n; i++) {
            dot += v1.get(i) * v2.get(i);
        }
        double s1 = 0;
        for (double a : v1) {
            s1 += a * a;
        }
        double s2 = 0;
        for (double b : v2) {
            s2 += b * b;
        }
        double norm1 = Math.sqrt(s1);
        double norm2 = Math.sqrt(s2);
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }
        return dot / (norm1 * norm2);
    }

    public double dietLevelWeight(String dietLevel) {
        String level = (dietLevel == null || dietLevel.isEmpty() ? "NONE" : dietLevel).toUpperCase(Locale.ROOT);
        return switch (level) {
            case "SOFT" -> 0.05;
            case "MEDIUM" -> 0.10;
            case "STRONG" -> 0.15;
            default -> 0.0;
        };
    }

    private static double[] dietMacroRatios(List<Double> v) {
        double protein = Math.max(v.size() > 0 ? v.get(0) : 0.0, 0.0);
        double fat = Math.max(v.size() > 1 ? v.get(1) : 0.0, 0.0);
        double carb = Math.max(v.size() > 2 ? v.get(2) : 0.0, 0.0);
        double fiber = Math.max(v.size() > 4 ? v.get(4) : 0.0, 0.0);
        double total = protein + fat + carb;
        if (total <= 0) {
            return new double[]{0.0, 0.0, 0.0, fiber};
        }
        return new double[]{protein / total, fat / total, carb / total, fiber};
    }

    public double dietAlignment(String dietMode, List<Double> dishVector) {
        String mode = (dietMode == null || dietMode.isEmpty() ? "NONE" : dietMode).toUpperCase(Locale.ROOT);
        if (mode.equals("NONE")) {
            return 0.5;
        }
        double[] r = dietMacroRatios(dishVector);
        double proteinRatio = r[0];
        double fatRatio = r[1];
        double carbRatio = r[2];
        double fiber = r[3];
        if ((proteinRatio + fatRatio + carbRatio) <= 0) {
            return 0.5;
        }
        switch (mode) {
            case "LOW_CARB":
                return clamp(1.0 - carbRatio);
            case "HIGH_PROTEIN":
                return clamp(proteinRatio);
            case "LOW_FAT":
                return clamp(1.0 - fatRatio);
            case "BALANCED": {
                double distance = Math.abs(proteinRatio - balancedTarget[0]) + Math.abs(fatRatio - balancedTarget[1])
                        + Math.abs(carbRatio - balancedTarget[2]);
                return clamp(1.0 - (distance / 1.4));
            }
            case "LIGHT": {
                double lowDensity = clamp(1.0 - (0.7 * fatRatio + 0.3 * carbRatio));
                double fiberBonus = clamp(fiber / 8.0);
                return clamp(0.85 * lowDensity + 0.15 * fiberBonus);
            }
            default:
                return 0.5;
        }
    }

    /** Django {@code _apply_user_vector_ema}. */
    public List<Double> applyUserVectorEma(List<Double> persisted, List<Double> history) {
        if (history == null || history.isEmpty()) {
            return persisted == null ? List.of() : persisted;
        }
        if (persisted == null || persisted.isEmpty() || persisted.size() != history.size()) {
            return history;
        }
        double alpha = userVectorEmaAlpha;
        List<Double> out = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            out.add((1.0 - alpha) * persisted.get(i) + alpha * history.get(i));
        }
        return out;
    }

    // =====================================================================
    // Map builders — Python-side processing of the data source rows
    // =====================================================================

    private Map<String, Double> historyRawMap(List<ScoringDataSource.OrderRow> rows, Instant now) {
        Map<String, Double> history = new LinkedHashMap<>();
        for (ScoringDataSource.OrderRow row : rows) {
            double ageDays = row.orderCreatedAt() != null ? PyMath.ageDays(now, row.orderCreatedAt()) : 0.0;
            double decay = Math.exp(-timeDecayLambda * ageDays);
            history.merge(row.dishId(), quantityOrOne(row.quantity()) * decay, Double::sum);
        }
        return history;
    }

    private static double quantityOrOne(Integer quantity) {
        return (quantity == null || quantity == 0) ? 1.0 : quantity.doubleValue();
    }

    private Map<String, Double> ingredientRatioMap(ScoringDataSource ds, List<String> dishIds,
                                                   List<String> favoriteIngredientIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> totals = ds.ingredientTotals(dishIds);
        Map<String, Double> ratio = new LinkedHashMap<>();
        if (favoriteIngredientIds == null || favoriteIngredientIds.isEmpty()) {
            totals.keySet().forEach(k -> ratio.put(k, 0.0));
            return ratio;
        }
        Map<String, Integer> matched = ds.ingredientMatches(dishIds, favoriteIngredientIds);
        totals.forEach((dishId, total) -> {
            if (total <= 0) {
                ratio.put(dishId, 0.0);
            } else {
                ratio.put(dishId, clamp((double) matched.getOrDefault(dishId, 0) / total));
            }
        });
        return ratio;
    }

    private Map<String, Double> favoriteRecencyMap(ScoringDataSource ds, long userId, List<String> dishIds, Instant now) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        dishIds.forEach(id -> result.put(id, 0.0));
        for (ScoringDataSource.FavoriteRow row : ds.favoriteRows(userId, dishIds)) {
            double ageDays = row.createdAt() != null ? PyMath.ageDays(now, row.createdAt()) : 0.0;
            double recency = Math.exp(-favoriteRecencyDecayLambda * ageDays);
            result.put(row.dishId(), clamp(recency));
        }
        return result;
    }

    private Map<String, Map<String, Double>> dishIssueMap(ScoringDataSource ds, List<String> dishIds, List<String> issueKeys) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (String dishId : dishIds) {
            Map<String, Double> perIssue = new LinkedHashMap<>();
            issueKeys.forEach(k -> perIssue.put(k, 0.0));
            result.put(dishId, perIssue);
        }
        for (ScoringDataSource.IssueRow row : ds.issueRows(dishIds)) {
            String issue = (row.issue() == null ? "" : row.issue()).strip().toLowerCase(Locale.ROOT);
            Map<String, Double> perIssue = result.get(row.dishId());
            if (!perIssue.containsKey(issue)) {
                continue;
            }
            double value = row.avgWeight() != null ? row.avgWeight() : 0.0;
            perIssue.put(issue, clamp(value));
        }
        return result;
    }

    /** Django {@code _get_dish_nutrition_vector_map}: weight-weighted mean, else unweighted mean of non-null values. */
    static Map<String, List<Double>> dishNutritionVectorMap(List<ScoringDataSource.NutritionRow> rows, List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, double[]> weightedSums = new HashMap<>();
        Map<String, Double> sumWeights = new HashMap<>();
        Map<String, double[]> unweightedSums = new HashMap<>();
        Map<String, int[]> unweightedCounts = new HashMap<>();
        for (ScoringDataSource.NutritionRow row : rows) {
            String dishId = row.dishId();
            double weightValue = row.weight() != null ? row.weight() : 0.0;
            double weight = weightValue > 0 ? weightValue : 0.0;
            Double[] raw = {row.protein(), row.lipid(), row.carbohydrate(), row.natri(), row.fiber()};
            double[] us = unweightedSums.computeIfAbsent(dishId, k -> new double[5]);
            int[] uc = unweightedCounts.computeIfAbsent(dishId, k -> new int[5]);
            for (int i = 0; i < 5; i++) {
                if (raw[i] != null) {
                    us[i] += raw[i];
                    uc[i] += 1;
                }
            }
            if (weight > 0) {
                double[] ws = weightedSums.computeIfAbsent(dishId, k -> new double[5]);
                for (int i = 0; i < 5; i++) {
                    ws[i] += (raw[i] != null ? raw[i] : 0.0) * weight;
                }
                sumWeights.merge(dishId, weight, Double::sum);
            }
        }
        Map<String, List<Double>> vectorMap = new LinkedHashMap<>();
        for (String dishId : new LinkedHashSet<>(dishIds)) {
            double sw = sumWeights.getOrDefault(dishId, 0.0);
            List<Double> vector = new ArrayList<>(5);
            if (sw > 0) {
                double[] ws = weightedSums.get(dishId);
                for (int i = 0; i < 5; i++) {
                    vector.add(ws[i] / sw);
                }
            } else {
                double[] us = unweightedSums.getOrDefault(dishId, new double[5]);
                int[] uc = unweightedCounts.getOrDefault(dishId, new int[5]);
                for (int i = 0; i < 5; i++) {
                    vector.add(uc[i] > 0 ? us[i] / uc[i] : 0.0);
                }
            }
            vectorMap.put(dishId, vector);
        }
        return vectorMap;
    }

    private static Map<String, Double> nutritionConfidenceMap(ScoringDataSource ds, List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> map = new LinkedHashMap<>();
        dishIds.forEach(id -> map.put(id, 0.0));
        for (ScoringDataSource.CoverageRow row : ds.coverageRows(dishIds)) {
            if (row.totalCount() <= 0) {
                map.put(row.dishId(), 0.0);
                continue;
            }
            double coverage = ((double) row.proteinCount() + (double) row.lipidCount() + (double) row.carbCount()
                    + (double) row.sodiumCount() + (double) row.fiberCount()) / (5.0 * row.totalCount());
            map.put(row.dishId(), clamp(coverage));
        }
        return map;
    }

    private List<Double> buildUserNutritionVector(ScoringDataSource ds, long userId,
                                                  Map<String, List<Double>> dishVectorMap,
                                                  List<Double> fallbackVector, Instant now) {
        if (dishVectorMap.isEmpty()) {
            return fallbackVector;
        }
        List<ScoringDataSource.OrderRow> rows = ds.recentHistoryRows(userId, new ArrayList<>(dishVectorMap.keySet()),
                now.minus(Duration.ofDays(180)));
        if (rows.isEmpty()) {
            return fallbackVector;
        }
        double[] accum = new double[5];
        double totalWeight = 0.0;
        for (ScoringDataSource.OrderRow row : rows) {
            List<Double> vector = dishVectorMap.get(row.dishId());
            if (vector == null || vector.isEmpty()) {
                continue;
            }
            double ageDays = row.orderCreatedAt() != null ? PyMath.ageDays(now, row.orderCreatedAt()) : 0.0;
            double decay = Math.exp(-timeDecayLambda * ageDays);
            double weight = quantityOrOne(row.quantity()) * decay;
            for (int i = 0; i < vector.size(); i++) {
                accum[i] += vector.get(i) * weight;
            }
            totalWeight += weight;
        }
        if (totalWeight <= 0) {
            return fallbackVector;
        }
        List<Double> out = new ArrayList<>(5);
        for (double v : accum) {
            out.add(v / totalWeight);
        }
        return out;
    }

    // =====================================================================
    // score()
    // =====================================================================

    public List<ScoredItem> score(ScoringDataSource ds, long userId, List<Dish> dishes, List<String> favoriteDishIds,
                                  List<String> favoriteIngredientIds, String dietMode, String dietLevel,
                                  Map<String, Double> issueProfile, double issueConfidence,
                                  List<Double> persistedUserVector) {
        if (dishes == null || dishes.isEmpty()) {
            return new ArrayList<>();
        }
        Instant now = clock.instant();
        List<String> dishIds = dishes.stream().map(d -> d.getUid().toString()).toList();
        List<String> issueKeys = new ArrayList<>(issueProfile.keySet());
        Collections.sort(issueKeys);

        Map<String, Integer> popularityMap = ds.popularityCounts(dishIds);
        Map<String, Double> historyRawMap = historyRawMap(ds.historyRows(userId, dishIds), now);
        Map<String, Double> ingredientRatioMap = ingredientRatioMap(ds, dishIds, favoriteIngredientIds);
        Map<String, Double> favoriteRecencyMap = favoriteRecencyMap(ds, userId, dishIds, now);
        Map<String, Map<String, Double>> dishIssueMap = dishIssueMap(ds, dishIds, issueKeys);
        Map<String, List<Double>> dishVectorMap = dishNutritionVectorMap(ds.nutritionRows(dishIds), dishIds);
        Map<String, Double> nutritionConfidenceMap = nutritionConfidenceMap(ds, dishIds);

        int maxPopularity = popularityMap.isEmpty() ? 1 : Collections.max(popularityMap.values());
        double maxHistoryRaw = historyRawMap.isEmpty() ? 1.0 : PyMath.pyMax(new ArrayList<>(historyRawMap.values()));

        List<Double> fallbackVector = new ArrayList<>(List.of(0.0, 0.0, 0.0, 0.0, 0.0));
        if (!dishVectorMap.isEmpty()) {
            double count = dishVectorMap.size();
            fallbackVector = new ArrayList<>(5);
            for (int i = 0; i < 5; i++) {
                double sum = 0;
                for (List<Double> v : dishVectorMap.values()) {
                    sum += v.get(i);
                }
                fallbackVector.add(sum / count);
            }
        }
        List<Double> historyUserVector = buildUserNutritionVector(ds, userId, dishVectorMap, fallbackVector, now);
        List<Double> userVector = applyUserVectorEma(persistedUserVector, historyUserVector);

        List<ScoredItem> scored = new ArrayList<>();
        double dietWeight = dietLevelWeight(dietLevel);
        boolean strongLevel = (dietLevel == null || dietLevel.isEmpty() ? "NONE" : dietLevel)
                .toUpperCase(Locale.ROOT).equals("STRONG");
        Set<String> favoriteSet = new LinkedHashSet<>(favoriteDishIds == null ? List.of() : favoriteDishIds);

        for (Dish dish : dishes) {
            String dishId = dish.getUid().toString();
            double ratingNorm = clamp(dish.getAvgRating() / 5.0);

            int popularity = popularityMap.getOrDefault(dishId, 0);
            double popularityNorm = clamp(maxPopularity > 0 ? Math.log1p(popularity) / Math.log1p(maxPopularity) : 0.0);

            double ageDays = PyMath.ageDays(now, dish.getCreatedAt());
            double recencyNorm = clamp(Math.exp(-recencyDecayLambda * ageDays));

            double baseScore = clamp(0.5 * ratingNorm + 0.3 * popularityNorm + 0.2 * recencyNorm);

            double binaryFavorite = favoriteSet.contains(dishId) ? 1.0 : 0.0;
            double ingredientRatio = ingredientRatioMap.getOrDefault(dishId, 0.0);
            double favoriteRecency = favoriteRecencyMap.getOrDefault(dishId, 0.0);
            double favoriteScore = clamp(0.6 * binaryFavorite + 0.2 * ingredientRatio + 0.2 * favoriteRecency);

            double historyRaw = historyRawMap.getOrDefault(dishId, 0.0);
            double historyScore;
            if (prioritizeUnorderedDishes && maxHistoryRaw > 0) {
                double orderFrequencyNormalized = clamp(historyRaw / maxHistoryRaw);
                historyScore = clamp(1.0 - orderFrequencyNormalized);
            } else {
                historyScore = clamp(maxHistoryRaw > 0 ? historyRaw / maxHistoryRaw : 0.0);
            }

            Map<String, Double> dishIssueProfile = dishIssueMap.getOrDefault(dishId, Map.of());
            double issueSum = 0;
            for (String key : issueKeys) {
                issueSum += issueProfile.getOrDefault(key, 0.0) * dishIssueProfile.getOrDefault(key, 0.0);
            }
            double issuePenalty = clamp(issueSum);

            List<Double> dishVector = dishVectorMap.getOrDefault(dishId, List.of(0.0, 0.0, 0.0, 0.0, 0.0));
            double cosine = cosineSimilarity(userVector, dishVector);
            double mismatchRaw = clamp(1.0 - cosine);
            double nutritionConfidence = nutritionConfidenceMap.getOrDefault(dishId, 0.0);
            double mismatch = clamp(mismatchRaw * nutritionConfidence);

            double dietAlignment = dietAlignment(dietMode, dishVector);
            double dietComponent = dietWeight * ((2.0 * dietAlignment) - 1.0);

            double strongGatePenalty = 0.0;
            if (strongLevel && dietAlignment < 0.35) {
                strongGatePenalty = (0.35 - dietAlignment) * 0.6;
            }

            double effectivePenalty;
            if (PyMath.pyMin(issuePenalty, mismatch) >= penaltyCorrelationThreshold) {
                effectivePenalty = PyMath.pyMax(issuePenalty, mismatch) + penaltyGamma * PyMath.pyMin(issuePenalty, mismatch);
            } else {
                effectivePenalty = issuePenalty + mismatch;
            }
            effectivePenalty = clamp(effectivePenalty);

            double penaltyConfidence = clamp(0.5 * issueConfidence + 0.5 * nutritionConfidence);
            double finalPenalty = clamp(effectivePenalty * penaltyConfidence);

            double finalScore = weights.get("w1") * baseScore
                    + weights.get("w2") * favoriteScore
                    + weights.get("w3") * historyScore
                    + dietComponent
                    - unifiedPenaltyWeight * finalPenalty
                    - strongGatePenalty;

            ScoredItem item = new ScoredItem();
            item.setDish(dish);
            item.setDishId(dishId);
            item.setScore(round(finalScore, 6));
            item.setBaseScore(round(baseScore, 6));
            item.setFavoriteScore(round(favoriteScore, 6));
            item.setHistoryScore(round(historyScore, 6));
            item.setIssuePenalty(round(issuePenalty, 6));
            item.setPreferenceNutritionMismatchPenalty(round(mismatch, 6));
            item.setNutritionConfidence(round(nutritionConfidence, 6));
            item.setPenaltyConfidence(round(penaltyConfidence, 6));
            item.setDietAlignment(round(dietAlignment, 6));
            item.setIngredientMatchRatio(round(ingredientRatio, 6));
            item.setDishVector(dishVector);
            item.setUserVector(userVector);
            scored.add(item);
        }
        sortByScoreDesc(scored);
        return scored;
    }

    /**
     * Python {@code list.sort(key=score, reverse=True)}: stable for equal keys, and -0.0 == 0.0
     * (unlike {@link Double#compare}).
     */
    static void sortByScoreDesc(List<ScoredItem> items) {
        items.sort((a, b) -> b.getScore() > a.getScore() ? 1 : (b.getScore() < a.getScore() ? -1 : 0));
    }
}
