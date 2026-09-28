package com.amomeal.marketplace.recommendation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL equivalents of the ORM queries in ../../backend/recommendation/services/scoring.py
 * (JdbcTemplate, autocommit — the pipeline is not atomic in Django either). Each method's javadoc
 * names the Django queryset it mirrors.
 */
@Component
@RequiredArgsConstructor
public class JdbcScoringDataSource implements ScoringDataSource {

    private final JdbcTemplate jdbc;

    static String[] arr(List<String> ids) {
        return ids.toArray(new String[0]);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    /** {@code OrderItem.filter(dish_id__in, order__status=COMPLETED).values("dish_id").annotate(count=Count("id"))}. */
    @Override
    public Map<String, Integer> popularityCounts(List<String> dishIds) {
        Map<String, Integer> map = new LinkedHashMap<>();
        jdbc.query("""
                SELECT oi.dish_uid::text AS dish_id, COUNT(oi.id) AS cnt
                FROM order_item oi JOIN "order" o ON o.uid = oi.order_uid
                WHERE oi.dish_uid = ANY(CAST(? AS uuid[])) AND o.status = 'COMPLETED'
                GROUP BY oi.dish_uid
                """, rs -> {
            map.put(rs.getString("dish_id"), rs.getInt("cnt"));
        }, (Object) arr(dishIds));
        return map;
    }

    private List<OrderRow> orderRows(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> {
            int q = rs.getInt("quantity");
            Integer quantity = rs.wasNull() ? null : q;
            return new OrderRow(rs.getString("dish_id"), quantity, instant(rs, "order_created_at"));
        }, args);
    }

    /** {@code OrderItem.filter(order__owner_id, order__status=COMPLETED, dish_id__in).values("dish_id", "quantity", "order__created_at")}. */
    @Override
    public List<OrderRow> historyRows(long userId, List<String> dishIds) {
        return orderRows("""
                SELECT oi.dish_uid::text AS dish_id, oi.quantity, o.created_at AS order_created_at
                FROM order_item oi JOIN "order" o ON o.uid = oi.order_uid
                WHERE o.owner_id = ? AND o.status = 'COMPLETED' AND oi.dish_uid = ANY(CAST(? AS uuid[]))
                """, userId, arr(dishIds));
    }

    /** {@code DishIngredient.filter(dish_id__in, deleted=False).values("dish_id").annotate(total=Count("uid"))}. */
    @Override
    public Map<String, Integer> ingredientTotals(List<String> dishIds) {
        Map<String, Integer> map = new LinkedHashMap<>();
        jdbc.query("""
                SELECT dish_uid::text AS dish_id, COUNT(uid) AS total FROM dish_ingredient
                WHERE dish_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE GROUP BY dish_uid
                """, rs -> {
            map.put(rs.getString("dish_id"), rs.getInt("total"));
        }, (Object) arr(dishIds));
        return map;
    }

    /** Same as {@link #ingredientTotals} plus {@code ingredient_id__in=favorite_ingredient_ids}. */
    @Override
    public Map<String, Integer> ingredientMatches(List<String> dishIds, List<String> favoriteIngredientIds) {
        Map<String, Integer> map = new LinkedHashMap<>();
        jdbc.query("""
                SELECT dish_uid::text AS dish_id, COUNT(uid) AS matched FROM dish_ingredient
                WHERE dish_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                  AND ingredient_uid = ANY(CAST(? AS uuid[]))
                GROUP BY dish_uid
                """, rs -> {
            map.put(rs.getString("dish_id"), rs.getInt("matched"));
        }, arr(dishIds), arr(favoriteIngredientIds));
        return map;
    }

    /** {@code CustomerFavoriteDish.filter(user_id, deleted=False, dish_id__in).values("dish_id", "created_at")}. */
    @Override
    public List<FavoriteRow> favoriteRows(long userId, List<String> dishIds) {
        return jdbc.query("""
                SELECT dish_uid::text AS dish_id, created_at FROM customer_favorite_dish
                WHERE user_id = ? AND deleted = FALSE AND dish_uid = ANY(CAST(? AS uuid[]))
                """, (rs, i) -> new FavoriteRow(rs.getString("dish_id"), instant(rs, "created_at")), userId, arr(dishIds));
    }

    /** {@code Review.filter(deleted=False, dish_id__in, issue__isnull=False).exclude(issue="").values("dish_id","issue").annotate(avg_weight=Avg("weight"))}. */
    @Override
    public List<IssueRow> issueRows(List<String> dishIds) {
        return jdbc.query("""
                SELECT dish_uid::text AS dish_id, issue, AVG(weight) AS avg_weight FROM review
                WHERE deleted = FALSE AND dish_uid = ANY(CAST(? AS uuid[])) AND issue IS NOT NULL AND issue <> ''
                GROUP BY dish_uid, issue
                """, (rs, i) -> new IssueRow(rs.getString("dish_id"), rs.getString("issue"), nullableDouble(rs, "avg_weight")),
                (Object) arr(dishIds));
    }

    /** {@code DishIngredient.filter(dish_id__in, deleted=False).values("dish_id","weight","protein","lipid","carbohydrate","natri","fiber")}. */
    @Override
    public List<NutritionRow> nutritionRows(List<String> dishIds) {
        return jdbc.query("""
                SELECT dish_uid::text AS dish_id, weight, protein, lipid, carbohydrate, natri, fiber FROM dish_ingredient
                WHERE dish_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                """, (rs, i) -> new NutritionRow(rs.getString("dish_id"), nullableDouble(rs, "weight"),
                nullableDouble(rs, "protein"), nullableDouble(rs, "lipid"), nullableDouble(rs, "carbohydrate"),
                nullableDouble(rs, "natri"), nullableDouble(rs, "fiber")), (Object) arr(dishIds));
    }

    /** {@code _get_nutrition_confidence_map}'s Count(..., filter=Q(x__isnull=False)) annotations. */
    @Override
    public List<CoverageRow> coverageRows(List<String> dishIds) {
        return jdbc.query("""
                SELECT dish_uid::text AS dish_id, COUNT(uid) AS total_count,
                       COUNT(uid) FILTER (WHERE protein IS NOT NULL) AS protein_count,
                       COUNT(uid) FILTER (WHERE lipid IS NOT NULL) AS lipid_count,
                       COUNT(uid) FILTER (WHERE carbohydrate IS NOT NULL) AS carb_count,
                       COUNT(uid) FILTER (WHERE natri IS NOT NULL) AS sodium_count,
                       COUNT(uid) FILTER (WHERE fiber IS NOT NULL) AS fiber_count
                FROM dish_ingredient WHERE dish_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                GROUP BY dish_uid
                """, (rs, i) -> new CoverageRow(rs.getString("dish_id"), rs.getLong("total_count"),
                rs.getLong("protein_count"), rs.getLong("lipid_count"), rs.getLong("carb_count"),
                rs.getLong("sodium_count"), rs.getLong("fiber_count")), (Object) arr(dishIds));
    }

    /** {@code _build_user_nutrition_vector}: + {@code order__created_at__gte=now-180d}. */
    @Override
    public List<OrderRow> recentHistoryRows(long userId, List<String> dishIds, Instant since) {
        return orderRows("""
                SELECT oi.dish_uid::text AS dish_id, oi.quantity, o.created_at AS order_created_at
                FROM order_item oi JOIN "order" o ON o.uid = oi.order_uid
                WHERE o.owner_id = ? AND o.status = 'COMPLETED' AND o.created_at >= ?
                  AND oi.dish_uid = ANY(CAST(? AS uuid[]))
                """, userId, since.atOffset(java.time.ZoneOffset.UTC), arr(dishIds));
    }
}
