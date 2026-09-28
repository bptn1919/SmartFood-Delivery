package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure (no-DB) halves of ../../backend/recommendation/services/vector_index.py, split out so
 * they can be pinned against the real Python (fixture keys {@code dish_vectors},
 * {@code user_vector_history*}, {@code parse_literal}).
 */
public final class VectorMath {

    public static final int VECTOR_DIMENSION = 5;

    private VectorMath() {
    }

    /** One {@code DishIngredient.values("dish_id","weight","protein","lipid","carbohydrate","natri","fiber","confidence")} row. */
    public record IngredientRow(String dishId, Double weight, Double protein, Double lipid, Double carbohydrate,
                                Double natri, Double fiber, Double confidence) {
    }

    /** One upsert payload entry: {@code (dish_id, vector, dish_confidence)}. */
    public record DishVectorPayload(String dishId, List<Double> vector, double confidence) {
    }

    /** One history row ({@code dish_id, quantity, order__created_at}); dishId null = the FK was SET_NULL. */
    public record HistoryRow(String dishId, Integer quantity, Instant orderCreatedAt) {
    }

    private static double orDefault(Double v, double dflt) {
        // Python `float(x or dflt)`: None AND 0.0 both fall back to the default.
        return (v == null || v == 0.0) ? dflt : v;
    }

    /**
     * Django {@code refresh_dish_vectors}' aggregation:
     * {@code vector = Σ(w·c·v)/Σ(w·c)}, {@code confidence = Σ(w·c)/Σ(w)}, sodium mg→g.
     *
     * <p>PORT-NOTE (faithful): {@code float(weight or 1.0)} / {@code float(confidence or 1.0)} turn a
     * 0 weight or 0 confidence into 1.0 (NOT skipped); only negative weights and confidences
     * outside [0, 1] are skipped.
     */
    public static List<DishVectorPayload> aggregateDishVectors(List<IngredientRow> rows) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // p, l, c, s, f, wcSum, wSum
        for (IngredientRow row : rows) {
            double weight = orDefault(row.weight(), 1.0);
            double confidence = orDefault(row.confidence(), 1.0);
            if (weight <= 0 || confidence < 0 || confidence > 1.0) {
                continue;
            }
            double protein = orDefault(row.protein(), 0.0);
            double lipid = orDefault(row.lipid(), 0.0);
            double carb = orDefault(row.carbohydrate(), 0.0);
            double sodium = orDefault(row.natri(), 0.0) / 1000.0;
            double fiber = orDefault(row.fiber(), 0.0);
            double[] a = acc.computeIfAbsent(row.dishId(), k -> new double[7]);
            double wc = weight * confidence;
            a[0] += wc * protein;
            a[1] += wc * lipid;
            a[2] += wc * carb;
            a[3] += wc * sodium;
            a[4] += wc * fiber;
            a[5] += wc;
            a[6] += weight;
        }
        List<DishVectorPayload> payload = new ArrayList<>();
        acc.forEach((dishId, a) -> {
            double wcSum = a[5];
            if (wcSum <= 0) {
                return;
            }
            List<Double> vector = List.of(a[0] / wcSum, a[1] / wcSum, a[2] / wcSum, a[3] / wcSum, a[4] / wcSum);
            double weightSum = a[6];
            double dishConfidence = weightSum > 0 ? a[5] / weightSum : 1.0;
            payload.add(new DishVectorPayload(dishId, vector, dishConfidence));
        });
        return payload;
    }

    /**
     * Django {@code _build_user_vector_from_history} (after the rows + dish vectors are loaded):
     * time-decayed, quantity-weighted mean of the dish vectors.
     */
    public static List<Double> userVectorFromHistory(List<HistoryRow> rows, Map<String, List<Double>> vectors,
                                                     double decayLambda, Instant now) {
        if (rows.isEmpty() || vectors.isEmpty()) {
            return List.of();
        }
        double[] accum = new double[VECTOR_DIMENSION];
        double totalWeight = 0.0;
        for (HistoryRow row : rows) {
            List<Double> vector = vectors.get(String.valueOf(row.dishId()));
            if (vector == null || vector.isEmpty()) {
                continue;
            }
            double ageDays = row.orderCreatedAt() != null ? PyMath.ageDays(now, row.orderCreatedAt()) : 0.0;
            double decay = Math.exp(-decayLambda * ageDays);
            double quantity = (row.quantity() == null || row.quantity() == 0) ? 1.0 : row.quantity();
            double weight = quantity * decay;
            totalWeight += weight;
            for (int i = 0; i < VECTOR_DIMENSION; i++) {
                accum[i] += vector.get(i) * weight;
            }
        }
        if (totalWeight <= 0) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(VECTOR_DIMENSION);
        for (double v : accum) {
            out.add(v / totalWeight);
        }
        return out;
    }

    /** Django {@code _parse_vector_literal}: "[a,b,c]" (brackets optional) → floats, [] on any error. */
    public static List<Double> parseVectorLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String raw = value.strip();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        try {
            List<Double> out = new ArrayList<>();
            for (String part : raw.split(",", -1)) {
                String p = part.strip();
                if (!p.isEmpty()) {
                    out.add(Double.parseDouble(p));
                }
            }
            return out;
        } catch (NumberFormatException e) {
            return List.of();
        }
    }
}
