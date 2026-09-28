package com.amomeal.marketplace.recommendation.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The DB reads {@link ScoringEngine#score} makes, at the same granularity as Django's ORM calls
 * in ../../backend/recommendation/services/scoring.py (aggregations the DB does stay aggregated,
 * rows that Python post-processes are returned as rows) — so every bit of Python-side math lives
 * in {@link ScoringEngine} and is pinned against the real code with a fake of this interface.
 * Production implementation: {@link JdbcScoringDataSource}.
 */
public interface ScoringDataSource {

    /** One {@code OrderItem.values("dish_id", "quantity", "order__created_at")} row. */
    record OrderRow(String dishId, Integer quantity, Instant orderCreatedAt) {
    }

    /** One {@code CustomerFavoriteDish.values("dish_id", "created_at")} row. */
    record FavoriteRow(String dishId, Instant createdAt) {
    }

    /** One {@code Review.values("dish_id", "issue").annotate(avg_weight=Avg("weight"))} row. */
    record IssueRow(String dishId, String issue, Double avgWeight) {
    }

    /** One {@code DishIngredient.values("dish_id", "weight", "protein", ...)} row (nullable fields). */
    record NutritionRow(String dishId, Double weight, Double protein, Double lipid, Double carbohydrate,
                        Double natri, Double fiber) {
    }

    /** One coverage-count row of {@code _get_nutrition_confidence_map}. */
    record CoverageRow(String dishId, long totalCount, long proteinCount, long lipidCount, long carbCount,
                       long sodiumCount, long fiberCount) {
    }

    /** {@code _get_popularity_map}: COMPLETED order-item count per dish. */
    Map<String, Integer> popularityCounts(List<String> dishIds);

    /** {@code _get_history_raw_map} rows: the user's COMPLETED order items for these dishes. */
    List<OrderRow> historyRows(long userId, List<String> dishIds);

    /** {@code _get_ingredient_ratio_map}: total non-deleted DishIngredient rows per dish. */
    Map<String, Integer> ingredientTotals(List<String> dishIds);

    /** {@code _get_ingredient_ratio_map}: rows whose ingredient is a favourite, per dish. */
    Map<String, Integer> ingredientMatches(List<String> dishIds, List<String> favoriteIngredientIds);

    /** {@code _get_favorite_recency_map} rows. */
    List<FavoriteRow> favoriteRows(long userId, List<String> dishIds);

    /** {@code _get_dish_issue_map} rows. */
    List<IssueRow> issueRows(List<String> dishIds);

    /** {@code _get_dish_nutrition_vector_map} rows. */
    List<NutritionRow> nutritionRows(List<String> dishIds);

    /** {@code _get_nutrition_confidence_map} rows. */
    List<CoverageRow> coverageRows(List<String> dishIds);

    /** {@code _build_user_nutrition_vector} rows: COMPLETED items of the last 180 days for these dishes. */
    List<OrderRow> recentHistoryRows(long userId, List<String> dishIds, Instant since);
}
