package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.recommendation.repository.RecommendationDishRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1:1 port of ../../backend/recommendation/services/candidates.py::CandidateGenerator — mixes six
 * sources by fixed ratio quotas, in priority order
 * {@code ann → vector_fallback → favorite → ingredient_similar → popular → trending},
 * de-duplicated with a seen-set, capped at {@code candidate_pool_size}.
 *
 * <p>PORT-NOTE (faithful): {@code use_pgvector_ann_candidates} only decides WHICH quota label
 * ({@code ann} 35% vs {@code vector_fallback} 25%) the vector source is counted under — both run
 * the identical {@code ORDER BY embedding <=> user_vector} SQL (Django does exactly this; see
 * RECOMMENDATION_FLOW.md §6). The optional {@code allergy_mode=HIDE} exclusion on the final
 * query does NOT filter {@code dish_ingredient.deleted} (Django's {@code .exclude(...)} doesn't),
 * while the vector SQL's NOT EXISTS does.
 *
 * <p>Plain class constructed per request like Django; DB access through JdbcTemplate (autocommit)
 * + the recommendation-owned dish repository for the final entity load.
 */
public class CandidateGenerator {

    private final int candidatePoolSize;
    private final JdbcTemplate jdbc;
    private final RecommendationDishRepository dishRepository;
    private final Clock clock;

    public CandidateGenerator(int candidatePoolSize, JdbcTemplate jdbc, RecommendationDishRepository dishRepository,
                              Clock clock) {
        this.candidatePoolSize = candidatePoolSize;
        this.jdbc = jdbc;
        this.dishRepository = dishRepository;
        this.clock = clock;
    }

    static List<String> asUuidList(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String t = v.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public List<Dish> generate(List<String> favoriteDishIds, List<String> favoriteIngredientIds,
                               List<String> allergicIngredientIds, String allergyMode, List<Double> userVector,
                               boolean useAnn, Integer maxPerCategory) {
        List<String> favoriteDishIdsNorm = asUuidList(favoriteDishIds);
        List<String> favoriteIngredientIdsNorm = asUuidList(favoriteIngredientIds);
        List<String> allergicIngredientIdsNorm = asUuidList(allergicIngredientIds);
        boolean hasUserVector = userVector != null && !userVector.isEmpty();

        Map<String, Integer> quotas = resolveSourceQuotas(candidatePoolSize, useAnn, hasUserVector);
        Map<String, List<String>> sources = new LinkedHashMap<>();
        for (String name : List.of("ann", "vector_fallback", "favorite", "ingredient_similar", "popular", "trending")) {
            sources.put(name, List.of());
        }

        if (useAnn && hasUserVector && quotas.get("ann") > 0) {
            sources.put("ann", vectorSourceIds(userVector, allergicIngredientIdsNorm, allergyMode, quotas.get("ann")));
        }
        if (!useAnn && hasUserVector && quotas.get("vector_fallback") > 0) {
            sources.put("vector_fallback", vectorSourceIds(userVector, allergicIngredientIdsNorm, allergyMode,
                    quotas.get("vector_fallback")));
        }
        if (quotas.get("popular") > 0) {
            sources.put("popular", jdbc.queryForList("""
                    SELECT d.uid::text FROM dish d
                    LEFT JOIN order_item oi ON oi.dish_uid = d.uid
                    LEFT JOIN "order" o ON o.uid = oi.order_uid
                    WHERE d.deleted = FALSE
                    GROUP BY d.uid
                    ORDER BY COUNT(DISTINCT oi.id) FILTER (WHERE o.status = 'COMPLETED') DESC,
                             d.avg_rating DESC, d.created_at DESC
                    LIMIT ?
                    """, String.class, quotas.get("popular")));
        }
        sources.put("favorite", favoriteDishIdsNorm.subList(0, Math.min(quotas.get("favorite"), favoriteDishIdsNorm.size())));
        if (!favoriteIngredientIdsNorm.isEmpty() && quotas.get("ingredient_similar") > 0) {
            sources.put("ingredient_similar", jdbc.queryForList("""
                    SELECT d.uid::text FROM dish d
                    JOIN dish_ingredient di ON di.dish_uid = d.uid
                    WHERE d.deleted = FALSE AND di.deleted = FALSE
                      AND di.ingredient_uid = ANY(CAST(? AS uuid[]))
                    GROUP BY d.uid
                    ORDER BY COUNT(di.uid) DESC, d.avg_rating DESC, d.created_at DESC
                    LIMIT ?
                    """, String.class, favoriteIngredientIdsNorm.toArray(new String[0]), quotas.get("ingredient_similar")));
        }
        if (quotas.get("trending") > 0) {
            Instant recentCutoff = clock.instant().minus(Duration.ofDays(14));
            sources.put("trending", jdbc.queryForList("""
                    SELECT d.uid::text FROM dish d
                    LEFT JOIN order_item oi ON oi.dish_uid = d.uid
                    LEFT JOIN "order" o ON o.uid = oi.order_uid
                    WHERE d.deleted = FALSE
                    GROUP BY d.uid
                    ORDER BY COUNT(DISTINCT oi.id) FILTER (WHERE o.status = 'COMPLETED' AND o.created_at >= ?) DESC,
                             d.avg_rating DESC, d.created_at DESC
                    LIMIT ?
                    """, String.class, recentCutoff.atOffset(ZoneOffset.UTC), quotas.get("trending")));
        }

        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        outer:
        for (List<String> ids : sources.values()) {
            for (String dishId : ids) {
                if (seen.contains(dishId)) {
                    continue;
                }
                seen.add(dishId);
                ordered.add(dishId);
                if (ordered.size() >= candidatePoolSize) {
                    break outer;
                }
            }
        }
        if (ordered.isEmpty()) {
            return new ArrayList<>();
        }

        // Dish.objects.filter(deleted=False, uid__in=ids) — a non-uuid id raises in Django (ValidationError).
        List<UUID> uids = ordered.stream().map(UUID::fromString).toList();
        List<Dish> dishes = dishRepository.findActiveWithAttachment(uids);
        if (allergyMode != null && allergyMode.toUpperCase(Locale.ROOT).equals("HIDE") && !allergicIngredientIdsNorm.isEmpty()) {
            Set<String> excluded = new HashSet<>(jdbc.queryForList("""
                    SELECT DISTINCT dish_uid::text FROM dish_ingredient
                    WHERE dish_uid = ANY(CAST(? AS uuid[])) AND ingredient_uid = ANY(CAST(? AS uuid[]))
                    """, String.class, ordered.toArray(new String[0]), allergicIngredientIdsNorm.toArray(new String[0])));
            dishes = dishes.stream().filter(d -> !excluded.contains(d.getUid().toString())).toList();
        }
        Map<String, Dish> dishMap = new HashMap<>();
        dishes.forEach(d -> dishMap.put(d.getUid().toString(), d));
        List<Dish> orderedDishes = new ArrayList<>();
        for (String id : ordered) {
            Dish d = dishMap.get(id);
            if (d != null) {
                orderedDishes.add(d);
            }
        }

        if (maxPerCategory != null && maxPerCategory > 0) {
            Map<String, Integer> categoryCounts = new HashMap<>();
            List<Dish> diversity = new ArrayList<>();
            for (Dish dish : orderedDishes) {
                String key = String.valueOf(dish.getCategory());
                int count = categoryCounts.getOrDefault(key, 0);
                categoryCounts.put(key, count);
                if (count >= maxPerCategory) {
                    continue;
                }
                categoryCounts.put(key, count + 1);
                diversity.add(dish);
                if (diversity.size() >= candidatePoolSize) {
                    break;
                }
            }
            orderedDishes = diversity;
        }
        if (orderedDishes.size() > candidatePoolSize) {
            orderedDishes = new ArrayList<>(orderedDishes.subList(0, candidatePoolSize));
        }
        return orderedDishes;
    }

    /** Django {@code _resolve_source_quotas} — pinned against Python (fixture key {@code quotas}). */
    public static Map<String, Integer> resolveSourceQuotas(int total, boolean useAnn, boolean hasUserVector) {
        Map<String, Double> ratios = new LinkedHashMap<>();
        ratios.put("ann", 0.35);
        ratios.put("vector_fallback", 0.25);
        ratios.put("favorite", 0.10);
        ratios.put("ingredient_similar", 0.20);
        ratios.put("popular", 0.20);
        ratios.put("trending", 0.15);
        if (!(useAnn && hasUserVector)) {
            ratios.put("ann", 0.0);
        }
        if (!(!useAnn && hasUserVector)) {
            ratios.put("vector_fallback", 0.0);
        }
        Map<String, Integer> quotas = new LinkedHashMap<>();
        // Python int(total * ratio) truncates toward zero.
        ratios.forEach((name, ratio) -> quotas.put(name, (int) (total * ratio)));
        ratios.forEach((name, ratio) -> {
            if (ratio > 0 && quotas.get(name) == 0) {
                quotas.put(name, 1);
            }
        });
        int allocated = quotas.values().stream().mapToInt(Integer::intValue).sum();
        if (allocated < total) {
            quotas.put("popular", quotas.get("popular") + (total - allocated));
        } else if (allocated > total) {
            int overflow = allocated - total;
            for (String name : List.of("trending", "popular", "ingredient_similar", "vector_fallback", "ann", "favorite")) {
                if (overflow <= 0) {
                    break;
                }
                int canReduce = Math.max(quotas.get(name) - (ratios.get(name) > 0 ? 1 : 0), 0);
                int step = Math.min(canReduce, overflow);
                quotas.put(name, quotas.get(name) - step);
                overflow -= step;
            }
        }
        return quotas;
    }

    /**
     * Django {@code _vector_source_ids}: pgvector cosine-distance ordering (+ HIDE-mode allergy
     * NOT EXISTS). Any SQL failure → [] (bare except). An all-zero vector returns [] up front.
     */
    public List<String> vectorSourceIds(List<Double> userVector, List<String> allergicIngredientIds,
                                        String allergyMode, int limit) {
        if (userVector == null || userVector.isEmpty() || userVector.stream().allMatch(v -> v == 0)) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT idx.dish_uid::text
                FROM recommendation_dish_vector_index idx
                INNER JOIN dish d
                    ON d.uid = idx.dish_uid
                AND d.deleted = FALSE
                """);
        List<Object> params = new ArrayList<>();
        if (allergyMode != null && allergyMode.toUpperCase(Locale.ROOT).equals("HIDE")
                && allergicIngredientIds != null && !allergicIngredientIds.isEmpty()) {
            sql.append("""
                     WHERE NOT EXISTS (
                        SELECT 1
                        FROM dish_ingredient di
                        WHERE di.dish_uid = d.uid
                        AND di.deleted = FALSE
                        AND di.ingredient_uid = ANY(CAST(? AS uuid[]))
                    )
                    """);
            params.add(allergicIngredientIds.toArray(new String[0]));
        }
        sql.append("""
                 ORDER BY idx.embedding <=> CAST(? AS vector)
                LIMIT ?
                """);
        params.add(PyMath.vectorLiteral(userVector));
        params.add(limit);
        try {
            return jdbc.queryForList(sql.toString(), String.class, params.toArray());
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
