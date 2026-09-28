package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1:1 port of ../../backend/recommendation/services/vector_index.py::VectorIndexService — builds
 * and stores the 5-dim nutrition vectors ([protein, lipid, carb, sodium(g), fiber]) in the two
 * pgvector tables via raw SQL (JdbcTemplate, autocommit), exactly like Django's
 * {@code connection.cursor()} code.
 *
 * <p><b>pgvector I/O</b>: vectors are written as text literals ({@code CAST(? AS vector)}) and read
 * back as {@code embedding::text}, then parsed with {@link VectorMath#parseVectorLiteral} — the
 * same round trip as Django. This is deliberate (and why the {@code com.pgvector:pgvector} Java
 * type is not used): pgvector stores float4, and its text output is the shortest decimal that
 * round-trips the float4; Python parses that decimal into a double. Reading a {@code float[]}
 * through PGvector and widening to double would give e.g. 0.10000000149 instead of 0.1 and
 * break numeric parity.
 *
 * <p>Every SQL block is fail-soft ({@code try/except Exception: return <empty>}) like Django —
 * PORT-NOTE: Django logs nothing on these failures; this port logs at DEBUG only.
 */
@Slf4j
@Service
public class VectorIndexService {

    public static final int VECTOR_REFRESH_WINDOW_MINUTES = 60;
    public static final int VECTOR_REFRESH_COOLDOWN_MINUTES = 5;
    public static final int VECTOR_REFRESH_BATCH_LIMIT = 500;
    public static final int USER_VECTOR_REFRESH_COOLDOWN_MINUTES = 1;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public VectorIndexService(JdbcTemplate jdbc, @Qualifier("recommendationClock") Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // =====================================================================
    // Dish vectors
    // =====================================================================

    public int ensureDishVectors() {
        return ensureDishVectors(null, null);
    }

    /** Django {@code ensure_dish_vectors}: full build when empty, else incremental refresh. */
    public int ensureDishVectors(Integer refreshWindowMinutes, Integer maxBatchSize) {
        int currentCount;
        try {
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_dish_vector_index", Integer.class);
            currentCount = c == null ? 0 : c;
        } catch (RuntimeException e) {
            log.debug("ensure_dish_vectors count failed", e);
            return 0;
        }
        if (currentCount > 0) {
            int window = (refreshWindowMinutes == null || refreshWindowMinutes == 0) ? VECTOR_REFRESH_WINDOW_MINUTES : refreshWindowMinutes;
            int batch = (maxBatchSize == null || maxBatchSize == 0) ? VECTOR_REFRESH_BATCH_LIMIT : maxBatchSize;
            int refreshed = refreshDishVectorsIncremental(window, batch);
            return refreshed != 0 ? refreshed : currentCount;
        }
        return refreshDishVectors(null);
    }

    /** Django {@code refresh_dish_vectors}: aggregate DishIngredient rows, upsert (ON CONFLICT). */
    public int refreshDishVectors(List<String> dishIds) {
        String sql = """
                SELECT di.dish_uid::text AS dish_id, di.weight, di.protein, di.lipid, di.carbohydrate,
                       di.natri, di.fiber, di.confidence
                FROM dish_ingredient di JOIN dish d ON d.uid = di.dish_uid
                WHERE di.deleted = FALSE AND d.deleted = FALSE
                """;
        List<Object> args = new ArrayList<>();
        if (dishIds != null && !dishIds.isEmpty()) {
            sql += " AND di.dish_uid = ANY(CAST(? AS uuid[]))";
            args.add(dishIds.toArray(new String[0]));
        }
        sql += " ORDER BY di.dish_uid";
        List<VectorMath.IngredientRow> rows = jdbc.query(sql, (rs, i) -> new VectorMath.IngredientRow(
                rs.getString("dish_id"),
                JdbcScoringDataSource.nullableDouble(rs, "weight"),
                JdbcScoringDataSource.nullableDouble(rs, "protein"),
                JdbcScoringDataSource.nullableDouble(rs, "lipid"),
                JdbcScoringDataSource.nullableDouble(rs, "carbohydrate"),
                JdbcScoringDataSource.nullableDouble(rs, "natri"),
                JdbcScoringDataSource.nullableDouble(rs, "fiber"),
                JdbcScoringDataSource.nullableDouble(rs, "confidence")), args.toArray());

        List<VectorMath.DishVectorPayload> payload = VectorMath.aggregateDishVectors(rows);
        if (payload.isEmpty()) {
            return 0;
        }
        try {
            List<Object[]> batch = payload.stream()
                    .map(p -> new Object[]{p.dishId(), PyMath.vectorLiteral(p.vector()), p.confidence()})
                    .toList();
            jdbc.batchUpdate("""
                    INSERT INTO recommendation_dish_vector_index (dish_uid, embedding, confidence, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS vector), ?, NOW())
                    ON CONFLICT (dish_uid)
                    DO UPDATE
                       SET embedding = EXCLUDED.embedding,
                           confidence = EXCLUDED.confidence,
                           updated_at = NOW()
                    """, batch);
            return payload.size();
        } catch (RuntimeException e) {
            log.debug("refresh_dish_vectors upsert failed", e);
            return 0;
        }
    }

    /** Django {@code refresh_dish_vectors_incremental}: dishes whose ingredients changed in the window. */
    public int refreshDishVectorsIncremental(int sinceMinutes, int limit) {
        Instant since = clock.instant().minus(Duration.ofMinutes(Math.max(1, sinceMinutes)));
        List<String> dishIds = jdbc.queryForList("""
                SELECT di.dish_uid::text FROM dish_ingredient di JOIN dish d ON d.uid = di.dish_uid
                WHERE di.deleted = FALSE AND d.deleted = FALSE AND di.updated_at >= ?
                GROUP BY di.dish_uid ORDER BY MAX(di.updated_at) DESC LIMIT ?
                """, String.class, since.atOffset(ZoneOffset.UTC), Math.max(1, limit));
        if (dishIds.isEmpty()) {
            return 0;
        }
        return refreshDishVectors(dishIds);
    }

    /** Django {@code refresh_dish_vectors_for_dishes} (the DishIngredient post_save/post_delete signal target). */
    public int refreshDishVectorsForDishes(List<String> dishIds, Integer freshnessMinutes) {
        if (dishIds == null || dishIds.isEmpty()) {
            return 0;
        }
        int freshness = (freshnessMinutes == null || freshnessMinutes == 0) ? VECTOR_REFRESH_COOLDOWN_MINUTES : freshnessMinutes;
        List<String> stale = filterStaleDishIds(dishIds, freshness);
        if (stale.isEmpty()) {
            return 0;
        }
        return refreshDishVectors(stale);
    }

    private List<String> filterStaleDishIds(List<String> dishIds, int freshnessMinutes) {
        Set<String> recent = new LinkedHashSet<>();
        try {
            recent.addAll(jdbc.queryForList("""
                    SELECT dish_uid::text FROM recommendation_dish_vector_index
                    WHERE dish_uid = ANY(CAST(? AS uuid[]))
                      AND updated_at >= NOW() - (? || ' minutes')::interval
                    """, String.class, dishIds.toArray(new String[0]), String.valueOf(Math.max(1, freshnessMinutes))));
        } catch (RuntimeException e) {
            log.debug("filter_stale_dish_ids failed", e);
        }
        return dishIds.stream().filter(id -> !recent.contains(id)).toList();
    }

    // =====================================================================
    // User vectors
    // =====================================================================

    public record UserVector(List<Double> vector, String source) {
    }

    /** Django {@code get_or_build_user_vector}: persisted → history (conf 0.8) → global mean (conf 0.4) → empty. */
    public UserVector getOrBuildUserVector(long userId, double decayLambda) {
        List<Double> persisted = getPersistedUserVector(userId);
        if (!persisted.isEmpty()) {
            return new UserVector(persisted, "persisted");
        }
        List<Double> history = buildUserVectorFromHistory(userId, decayLambda);
        if (!history.isEmpty()) {
            upsertUserVector(userId, history, 0.8);
            return new UserVector(history, "history");
        }
        List<Double> globalMean = globalMeanDishVector();
        if (!globalMean.isEmpty()) {
            upsertUserVector(userId, globalMean, 0.4);
            return new UserVector(globalMean, "global_mean");
        }
        return new UserVector(List.of(), "empty");
    }

    /** Django {@code refresh_user_vector} (order-completed / feature-changed signal target). */
    public String refreshUserVector(long userId, Double decayLambda, boolean force) {
        if (!force && isUserVectorFresh(userId, USER_VECTOR_REFRESH_COOLDOWN_MINUTES)) {
            return "cooldown";
        }
        double lambda = (decayLambda == null || decayLambda == 0.0) ? 0.03 : decayLambda;
        List<Double> history = buildUserVectorFromHistory(userId, lambda);
        if (!history.isEmpty()) {
            upsertUserVector(userId, history, 0.8);
            return "history";
        }
        List<Double> globalMean = globalMeanDishVector();
        if (!globalMean.isEmpty()) {
            upsertUserVector(userId, globalMean, 0.4);
            return "global_mean";
        }
        return "empty";
    }

    public List<Double> getPersistedUserVector(long userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT embedding::text FROM recommendation_user_vector_index WHERE user_id = ?", String.class, userId);
            if (rows.isEmpty()) {
                return List.of();
            }
            return VectorMath.parseVectorLiteral(rows.get(0));
        } catch (RuntimeException e) {
            log.debug("get_persisted_user_vector failed", e);
            return List.of();
        }
    }

    boolean isUserVectorFresh(long userId, int freshnessMinutes) {
        Instant updatedAt;
        try {
            List<Timestamp> rows = jdbc.queryForList(
                    "SELECT updated_at FROM recommendation_user_vector_index WHERE user_id = ?", Timestamp.class, userId);
            if (rows.isEmpty() || rows.get(0) == null) {
                return false;
            }
            updatedAt = rows.get(0).toInstant();
        } catch (RuntimeException e) {
            return false;
        }
        int freshness = Math.max(1, freshnessMinutes);
        return !updatedAt.isBefore(clock.instant().minus(Duration.ofMinutes(freshness)));
    }

    /**
     * Django {@code _build_user_vector_from_history}. PORT-NOTE (faithful): Django builds the id
     * list with {@code str(row["dish_id"])}, so an order item whose dish was deleted (FK SET_NULL)
     * contributes the string {@code "None"}; the {@code CAST(... AS uuid[])} then fails, the
     * bare except returns {}, and the WHOLE history vector comes back empty (→ global mean).
     * Reproduced by sending the same "None" string.
     */
    public List<Double> buildUserVectorFromHistory(long userId, double decayLambda) {
        Instant now = clock.instant();
        List<VectorMath.HistoryRow> rows = jdbc.query("""
                SELECT oi.dish_uid::text AS dish_id, oi.quantity, o.created_at AS order_created_at
                FROM order_item oi JOIN "order" o ON o.uid = oi.order_uid
                WHERE o.owner_id = ? AND o.status = 'COMPLETED' AND o.created_at >= ?
                ORDER BY o.created_at DESC
                """, (rs, i) -> {
            int q = rs.getInt("quantity");
            Integer quantity = rs.wasNull() ? null : q;
            return new VectorMath.HistoryRow(rs.getString("dish_id"), quantity,
                    JdbcScoringDataSource.instant(rs, "order_created_at"));
        }, userId, now.minus(Duration.ofDays(180)).atOffset(ZoneOffset.UTC));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> dishIds = new LinkedHashSet<>();
        rows.forEach(r -> dishIds.add(String.valueOf(r.dishId()).equals("null") ? "None" : r.dishId()));
        Map<String, List<Double>> vectors = getDishVectors(new ArrayList<>(dishIds));
        if (vectors.isEmpty()) {
            return List.of();
        }
        return VectorMath.userVectorFromHistory(rows, vectors, decayLambda, now);
    }

    public List<Double> globalMeanDishVector() {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT AVG((embedding::text)::vector)::text FROM recommendation_dish_vector_index", String.class);
            if (rows.isEmpty() || rows.get(0) == null) {
                // Django: str(None) -> "None" -> float("None") raises -> []
                return List.of();
            }
            return VectorMath.parseVectorLiteral(rows.get(0));
        } catch (RuntimeException e) {
            log.debug("global_mean_dish_vector failed", e);
            return List.of();
        }
    }

    void upsertUserVector(long userId, List<Double> vector, double confidence) {
        if (vector == null || vector.isEmpty()) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO recommendation_user_vector_index (user_id, embedding, confidence, updated_at)
                    VALUES (?, CAST(? AS vector), ?, NOW())
                    ON CONFLICT (user_id)
                    DO UPDATE
                       SET embedding = EXCLUDED.embedding,
                           confidence = EXCLUDED.confidence,
                           updated_at = NOW()
                    """, userId, PyMath.vectorLiteral(vector), confidence);
        } catch (RuntimeException e) {
            log.debug("upsert_user_vector failed", e);
        }
    }

    /** Django {@code _get_dish_vectors}: {} on any failure (e.g. a non-uuid id string). */
    public Map<String, List<Double>> getDishVectors(List<String> dishIds) {
        if (dishIds == null || dishIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, List<Double>> out = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT dish_uid::text AS dish_id, embedding::text AS embedding
                    FROM recommendation_dish_vector_index WHERE dish_uid = ANY(CAST(? AS uuid[]))
                    """, rs -> {
                out.put(rs.getString("dish_id"), VectorMath.parseVectorLiteral(rs.getString("embedding")));
            }, (Object) dishIds.toArray(new String[0]));
            return out;
        } catch (RuntimeException e) {
            log.debug("get_dish_vectors failed", e);
            return Map.of();
        }
    }

    /** {@code SELECT embedding::text FROM recommendation_dish_vector_index WHERE dish_uid = ?} (find_better_dish_for_issue). */
    public List<Double> getDishVector(String dishUid) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT embedding::text FROM recommendation_dish_vector_index WHERE dish_uid = CAST(? AS uuid)",
                    String.class, dishUid);
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            return VectorMath.parseVectorLiteral(rows.get(0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** MMR's {@code _cosine_similarity_pgvector}: {@code SELECT 1 - (a <=> b)}; null = unavailable. */
    public Double pgvectorCosineSimilarity(List<Double> v1, List<Double> v2) {
        try {
            List<Double> rows = jdbc.queryForList("SELECT 1 - (CAST(? AS vector) <=> CAST(? AS vector))",
                    Double.class, PyMath.vectorLiteral(v1), PyMath.vectorLiteral(v2));
            if (rows.isEmpty()) {
                return 0.0;
            }
            Double v = rows.get(0);
            // Python: float(row[0] or 0.0) — NULL and 0.0 both give 0.0 (NaN is truthy and kept).
            return v == null ? 0.0 : v;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
