package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.recommendation.dto.RecommendationDishItemResponse;
import com.amomeal.marketplace.recommendation.dto.RecommendationFeedResponse;
import com.amomeal.marketplace.recommendation.entity.UserFoodPreferenceFeature;
import com.amomeal.marketplace.recommendation.repository.UserFoodPreferenceFeatureRepository;
import com.amomeal.marketplace.recommendation.support.RecommendationIntegrationTestBase;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The feed pipeline on a REAL Postgres with the pgvector extension (pgvector/pgvector:pg16):
 * vector upserts/reads through {@code CAST(? AS vector)} / {@code embedding::text}, the
 * {@code <=>} cosine ordering, {@code AVG(vector)}, the history / global-mean user vector, and the
 * signal equivalents (order completed, DishIngredient saved).
 */
class RecommendationPipelineTest extends RecommendationIntegrationTestBase {

    @Autowired RecommendationPipelineService pipeline;
    @Autowired VectorIndexService vectorIndex;
    @Autowired UserFoodPreferenceFeatureRepository featureRepository;

    private List<Double> storedDishVector(Dish dish) {
        return VectorMath.parseVectorLiteral(jdbc.queryForObject(
                "SELECT embedding::text FROM recommendation_dish_vector_index WHERE dish_uid = ?", String.class, dish.getUid()));
    }

    private static void assertClose(List<Double> expected, List<Double> actual, double relTol) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), Math.max(Math.abs(expected.get(i)), 1e-3) * relTol, "dim " + i);
        }
    }

    @Test
    void feed_endToEnd_historyVector_pgvectorRoundTrip_andExplainReasons() throws Exception {
        var chef = chef("recchef");
        var customer = register("reccust", UserRole.CUSTOMER);
        Dish protein = dish(chef, unique("Ức gà áp chảo"), 4.6);
        Dish carbs = dish(chef, unique("Mì xào giòn"), 3.9);
        line(protein, null, 150, 45, 6, 2, 300, 1, 0.9);
        line(protein, null, 50, 2, 1, 10, 20, 2, 1.0);
        line(carbs, null, 200, 8, 10, 90, 800, 3, 0.8);
        // Real flow: items exist before the order transitions to COMPLETED (the signal reads history then).
        var order = order(customer, protein, 2, OrderStatus.DELIVERING, LocalTime.NOON);
        // Faithful Django quirk: the DishIngredient signal skips dishes refreshed < 5 min ago, so the
        // 2nd line did not update the vector yet; the order signal would persist a user vector built
        // from that stale dish vector (and the persisted one wins afterwards). Bring it up to date first.
        assertClose(List.of(45.0, 6.0, 2.0, 0.3, 1.0), storedDishVector(protein), 1e-6);
        vectorIndex.refreshDishVectors(List.of(protein.getUid().toString()));
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        RecommendationFeedResponse feed = pipeline.recommendForUser(customer.id(), 100, 0, true);

        // Dish vectors were written to pgvector and read back as text exactly like Django (float4 storage).
        List<Double> expected = VectorMath.aggregateDishVectors(List.of(
                new VectorMath.IngredientRow("p", 150.0, 45.0, 6.0, 2.0, 300.0, 1.0, 0.9),
                new VectorMath.IngredientRow("p", 50.0, 2.0, 1.0, 10.0, 20.0, 2.0, 1.0))).get(0).vector();
        assertClose(expected, storedDishVector(protein), 1e-6);

        // The order-completed signal (Hibernate listener, after commit) + get_or_build persisted the
        // history vector: one ordered dish => user vector == that dish's stored vector.
        assertThat(feed.meta().userVectorSource()).isIn("persisted", "history");
        assertClose(storedDishVector(protein), vectorIndex.getPersistedUserVector(customer.id()), 1e-9);
        Double conf = jdbc.queryForObject("SELECT confidence FROM recommendation_user_vector_index WHERE user_id = ?",
                Double.class, customer.id());
        assertEquals(0.8, conf, 1e-6);

        RecommendationDishItemResponse proteinItem = feed.items().stream()
                .filter(i -> i.dishUid().equals(protein.getUid().toString())).findFirst().orElseThrow();
        assertThat(proteinItem.historyScore()).isEqualTo(1.0);
        assertThat(proteinItem.reasons()).contains("Được đề xuất dựa trên lịch sử gần đây của bạn");
        assertThat(proteinItem.nutritionPenalty()).isEqualTo(proteinItem.preferenceNutritionMismatchPenalty());
        assertThat(feed.items()).anyMatch(i -> i.dishUid().equals(carbs.getUid().toString()));
        assertThat(feed.meta().weights()).isEqualTo(Map.of("w1", 0.4, "w2", 0.2, "w3", 0.2, "w4", 0.1, "w5", 0.1));
        assertThat(feed.meta().annCandidatesEnabled()).isFalse();
        assertThat(feed.meta().candidateCount()).isEqualTo(feed.meta().scoredCount());

        // paging happens after MMR: offset 1 page == items[1:]
        RecommendationFeedResponse page2 = pipeline.recommendForUser(customer.id(), 1, 1, false);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).reasons()).isEmpty();
    }

    @Test
    void vectorSource_ordersByPgvectorCosineDistance_andHideModeExcludesAllergens() throws Exception {
        var chef = chef("vecchef");
        Ingredient peanut = ingredient("Đậu phộng");
        Dish fiberOnly = dish(chef, unique("Salad rau"), 4.0);
        Dish fiberAllergen = dish(chef, unique("Salad đậu"), 4.0);
        line(fiberOnly, null, 100, 0.001, 0.001, 0.001, 0.001, 50, 1.0);
        line(fiberAllergen, peanut, 100, 0.002, 0.001, 0.001, 0.001, 50, 1.0);
        vectorIndex.refreshDishVectors(List.of(fiberOnly.getUid().toString(), fiberAllergen.getUid().toString()));

        CandidateGenerator gen = pipeline.newCandidateGenerator(300);
        List<Double> query = List.of(0.0, 0.0, 0.0, 0.0, 1.0);
        List<String> nearest = gen.vectorSourceIds(query, List.of(), "", 2);
        assertThat(nearest).containsExactlyInAnyOrder(fiberOnly.getUid().toString(), fiberAllergen.getUid().toString());

        List<String> hidden = gen.vectorSourceIds(query, List.of(peanut.getUid().toString()), "HIDE", 50);
        assertThat(hidden).contains(fiberOnly.getUid().toString()).doesNotContain(fiberAllergen.getUid().toString());
        assertThat(gen.vectorSourceIds(query, List.of(peanut.getUid().toString()), "WARN", 2))
                .contains(fiberAllergen.getUid().toString());
        // all-zero vector => [] up front (Django)
        assertThat(gen.vectorSourceIds(List.of(0.0, 0.0, 0.0, 0.0, 0.0), List.of(), "", 5)).isEmpty();
        // a malformed allergen id makes the SQL fail => [] (bare except)
        assertThat(gen.vectorSourceIds(query, List.of("not-a-uuid"), "HIDE", 5)).isEmpty();

        // MMR's optional pgvector similarity: 1 - (a <=> b) computed in float4 by Postgres.
        double sql = vectorIndex.pgvectorCosineSimilarity(List.of(1.0, 2.0, 3.0, 0.0, 1.0), List.of(3.0, 2.0, 1.0, 1.0, 0.0));
        assertEquals(ScoringEngine.cosineSimilarity(List.of(1.0, 2.0, 3.0, 0.0, 1.0), List.of(3.0, 2.0, 1.0, 1.0, 0.0)), sql, 1e-6);
        MmrReranker pg = new MmrReranker(0.5, true, vectorIndex::pgvectorCosineSimilarity);
        List<ScoredItem> items = new ArrayList<>();
        for (double[] v : new double[][]{{1, 0, 0, 0, 0}, {0.9, 0.1, 0, 0, 0}, {0, 1, 0, 0, 0}}) {
            ScoredItem s = new ScoredItem();
            s.setScore(0.9 - items.size() * 0.05);
            s.setDishId("d" + items.size());
            s.setDishVector(List.of(v[0], v[1], v[2], v[3], v[4]));
            items.add(s);
        }
        assertThat(pg.rerank(items, 3).stream().map(ScoredItem::getDishId).toList())
                .isEqualTo(new MmrReranker(0.5).rerank(items, 3).stream().map(ScoredItem::getDishId).toList());
    }

    @Test
    void newUserWithoutHistory_getsGlobalMeanVector_withConfidence04() throws Exception {
        var chef = chef("gmchef");
        Dish d = dish(chef, unique("Cơm tấm"), 4.0);
        line(d, null, 100, 10, 10, 50, 500, 2, 1.0);
        var fresh = register("gmcust", UserRole.CUSTOMER);

        RecommendationFeedResponse feed = pipeline.recommendForUser(fresh.id(), 5, 0, false);
        assertThat(feed.meta().userVectorSource()).isEqualTo("global_mean");
        List<Double> mean = VectorMath.parseVectorLiteral(jdbc.queryForObject(
                "SELECT AVG((embedding::text)::vector)::text FROM recommendation_dish_vector_index", String.class));
        assertThat(vectorIndex.getPersistedUserVector(fresh.id())).isEqualTo(mean);
        assertEquals(0.4, jdbc.queryForObject("SELECT confidence FROM recommendation_user_vector_index WHERE user_id = ?",
                Double.class, fresh.id()), 1e-6);
        // second call reads the persisted row
        assertThat(pipeline.recommendForUser(fresh.id(), 5, 0, false).meta().userVectorSource()).isEqualTo("persisted");
    }

    @Test
    void allergyMode_warnFlagsDish_hideRemovesItFromTheFeed() throws Exception {
        var chef = chef("alchef");
        Ingredient shrimp = ingredient("Tôm");
        Dish allergenDish = dish(chef, unique("Bún tôm"), 5.0);
        line(allergenDish, shrimp, 100, 20, 5, 30, 400, 1, 1.0);
        var customer = register("alcust", UserRole.CUSTOMER);
        UserFoodPreferenceFeature feature = featureRepository.save(UserFoodPreferenceFeature.builder()
                .userId(customer.id())
                .allergicIngredientIds(new ArrayList<>(List.of(shrimp.getUid().toString())))
                .favoriteDishIds(new ArrayList<>(List.of(allergenDish.getUid().toString())))
                .allergyMode(AllergyMode.WARN).build());

        RecommendationDishItemResponse item = pipeline.recommendForUser(customer.id(), 100, 0, false).items().stream()
                .filter(i -> i.dishUid().equals(allergenDish.getUid().toString())).findFirst().orElseThrow();
        assertThat(item.allergyWarning()).isTrue();
        assertThat(item.isFavorite()).isTrue();

        feature.setAllergyMode(AllergyMode.HIDE);
        featureRepository.save(feature);
        assertThat(pipeline.recommendForUser(customer.id(), 100, 0, false).items())
                .noneMatch(i -> i.dishUid().equals(allergenDish.getUid().toString()));
    }

    @Test
    void dishIngredientSaveSignal_refreshesThatDishVectorAfterCommit() throws Exception {
        var chef = chef("sigchef");
        Dish d = dish(chef, unique("Canh chua"), 4.0);
        line(d, null, 100, 5, 1, 5, 900, 1, 1.0);
        // refresh_dish_vectors_for_dishes ran on commit via the Hibernate listener
        List<Double> v = storedDishVector(d);
        assertClose(List.of(5.0, 1.0, 5.0, 0.9, 1.0), v, 1e-6);
    }
}
