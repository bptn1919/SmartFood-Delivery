package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.service.DishNutritionService;
import com.amomeal.marketplace.recommendation.entity.UserDailyNutrition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Numeric parity with the REAL Django code. Every expected value comes from
 * {@code src/test/resources/recommendation/python_vectors.json}, produced by running
 * {@code gen_vectors.py} (same folder) in backend/venv: it imports the untouched Django services
 * and calls them on fixed inputs, replacing only the ORM/cursor calls with fixed rows. The same
 * inputs are read back here from the fixture's {@code inputs} section.
 *
 * <p>Rounded outputs (Django {@code round(x, 3|4|6)}) are compared EXACTLY; unrounded doubles
 * (raw vectors) to 1e-12 relative — the only admissible difference is float summation order
 * where Python iterates a {@code set}.
 */
class PythonParityTest {

    private static JsonNode V;
    private static JsonNode IN;
    private static final JsonMapper M = JsonMapper.builder().build();
    private static final Instant NOW = OffsetDateTime.parse("2026-09-23T12:00:00+00:00").toInstant();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = PythonParityTest.class.getResourceAsStream("/recommendation/python_vectors.json")) {
            V = M.readTree(in);
        }
        IN = V.get("inputs");
    }

    // ------------------------------------------------------------------ helpers

    private static Double dbl(JsonNode n) {
        return n == null || n.isNull() ? null : n.asDouble();
    }

    private static Integer integer(JsonNode n) {
        return n == null || n.isNull() ? null : n.asInt();
    }

    private static String str(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }

    private static Instant instant(JsonNode n) {
        return n == null || n.isNull() ? null : OffsetDateTime.parse(n.asString()).toInstant();
    }

    private static List<Double> doubles(JsonNode arr) {
        List<Double> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asDouble()));
        return out;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asString()));
        return out;
    }

    private static Map<String, Double> doubleMap(JsonNode obj) {
        Map<String, Double> out = new LinkedHashMap<>();
        obj.properties().forEach(e -> out.put(e.getKey(), dbl(e.getValue())));
        return out;
    }

    private static void assertVectorClose(List<Double> expected, List<Double> actual) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            double e = expected.get(i);
            assertEquals(e, actual.get(i), Math.max(Math.abs(e), 1.0) * 1e-12, "index " + i);
        }
    }

    private static Map<?, ?> json(String text) {
        return M.readValue(text, Map.class);
    }

    // ------------------------------------------------------------------ scoring helpers

    private static ScoringEngine defaultEngine() {
        return new ScoringEngine(0.03, 0.02, weights(), 0.25, 0.35, List.of(0.3, 0.3, 0.4), 0.03, 0.35, false, CLOCK);
    }

    private static Map<String, Double> weights() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("w1", 0.4);
        w.put("w2", 0.2);
        w.put("w3", 0.2);
        w.put("w4", 0.1);
        w.put("w5", 0.1);
        return w;
    }

    @Test
    void dietAlignmentLevelWeightEmaCosineAndBalancedTarget() {
        ScoringEngine engine = defaultEngine();
        for (JsonNode c : V.get("diet_alignment")) {
            assertEquals(c.get(2).asDouble(), engine.dietAlignment(str(c.get(0)), doubles(c.get(1))), 0.0, c.toString());
        }
        for (JsonNode c : V.get("diet_level_weight")) {
            assertEquals(c.get(1).asDouble(), engine.dietLevelWeight(str(c.get(0))), 0.0, c.toString());
        }
        ScoringEngine bt = new ScoringEngine(0.03, 0.02, Map.of("w1", 0.4), 0.25, 0.35, List.of(2.0, 1.0, 1.0), null, 0.35, false, CLOCK);
        assertThat(Arrays.stream(bt.balancedTarget()).boxed().toList()).isEqualTo(doubles(V.get("balanced_target_normalized")));
        ScoringEngine zero = new ScoringEngine(0.03, 0.02, Map.of(), 0.25, 0.35, List.of(0.0, 0.0, 0.0), null, 0.35, false, CLOCK);
        assertThat(Arrays.stream(zero.balancedTarget()).boxed().toList()).isEqualTo(doubles(V.get("balanced_target_zero")));
        assertEquals(V.get("unified_penalty_default").asDouble(), engine.unifiedPenaltyWeight(), 0.0);
        for (JsonNode c : V.get("ema")) {
            assertThat(engine.applyUserVectorEma(doubles(c.get(0)), doubles(c.get(1)))).isEqualTo(doubles(c.get(2)));
        }
        for (JsonNode c : V.get("cosine")) {
            assertEquals(c.get(2).asDouble(), ScoringEngine.cosineSimilarity(doubles(c.get(0)), doubles(c.get(1))), 0.0, c.toString());
        }
    }

    /** Fake data source returning exactly the rows the Python harness fed the Django ORM calls. */
    private static ScoringDataSource fixtureDataSource() {
        return new ScoringDataSource() {
            private List<OrderRow> orders() {
                List<OrderRow> rows = new ArrayList<>();
                IN.get("order_rows").forEach(r -> rows.add(new OrderRow(str(r.get("dish_id")), integer(r.get("quantity")),
                        instant(r.get("order__created_at")))));
                return rows;
            }

            @Override
            public Map<String, Integer> popularityCounts(List<String> dishIds) {
                Map<String, Integer> m = new LinkedHashMap<>();
                IN.get("popularity_rows").forEach(r -> m.put(str(r.get("dish_id")), r.get("count").asInt()));
                return m;
            }

            @Override
            public List<OrderRow> historyRows(long userId, List<String> dishIds) {
                return orders();
            }

            @Override
            public Map<String, Integer> ingredientTotals(List<String> dishIds) {
                Map<String, Integer> m = new LinkedHashMap<>();
                IN.get("ingredient_total_rows").forEach(r -> m.put(str(r.get("dish_id")), r.get("total").asInt()));
                return m;
            }

            @Override
            public Map<String, Integer> ingredientMatches(List<String> dishIds, List<String> favoriteIngredientIds) {
                Map<String, Integer> m = new LinkedHashMap<>();
                IN.get("ingredient_match_rows").forEach(r -> m.put(str(r.get("dish_id")), r.get("matched").asInt()));
                return m;
            }

            @Override
            public List<FavoriteRow> favoriteRows(long userId, List<String> dishIds) {
                List<FavoriteRow> rows = new ArrayList<>();
                IN.get("favorite_rows").forEach(r -> rows.add(new FavoriteRow(str(r.get("dish_id")), instant(r.get("created_at")))));
                return rows;
            }

            @Override
            public List<IssueRow> issueRows(List<String> dishIds) {
                List<IssueRow> rows = new ArrayList<>();
                IN.get("issue_rows").forEach(r -> rows.add(new IssueRow(str(r.get("dish_id")), str(r.get("issue")), dbl(r.get("avg_weight")))));
                return rows;
            }

            @Override
            public List<NutritionRow> nutritionRows(List<String> dishIds) {
                List<NutritionRow> rows = new ArrayList<>();
                IN.get("nutrition_rows").forEach(r -> rows.add(new NutritionRow(str(r.get("dish_id")), dbl(r.get("weight")),
                        dbl(r.get("protein")), dbl(r.get("lipid")), dbl(r.get("carbohydrate")), dbl(r.get("natri")), dbl(r.get("fiber")))));
                return rows;
            }

            @Override
            public List<CoverageRow> coverageRows(List<String> dishIds) {
                List<CoverageRow> rows = new ArrayList<>();
                IN.get("coverage_rows").forEach(r -> rows.add(new CoverageRow(str(r.get("dish_id")), r.get("total_count").asLong(),
                        r.get("protein_count").asLong(), r.get("lipid_count").asLong(), r.get("carb_count").asLong(),
                        r.get("sodium_count").asLong(), r.get("fiber_count").asLong())));
                return rows;
            }

            @Override
            public List<OrderRow> recentHistoryRows(long userId, List<String> dishIds, Instant since) {
                return orders();
            }
        };
    }

    private static List<Dish> fixtureDishes() {
        List<Dish> dishes = new ArrayList<>();
        IN.get("dishes").forEach(d -> dishes.add(Dish.builder().uid(UUID.fromString(str(d.get("uid"))))
                .avgRating(d.get("avg_rating").asDouble()).createdAt(instant(d.get("created_at"))).build()));
        return dishes;
    }

    private static Map<String, Double> fixtureIssueProfile() {
        return doubleMap(V.get("score_inputs").get("issue_profile"));
    }

    private static void assertScored(JsonNode expected, List<ScoredItem> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            ScoredItem a = actual.get(i);
            String ctx = "item " + i + " " + e.get("dish_id");
            assertEquals(str(e.get("dish_id")), a.getDishId(), ctx);
            assertEquals(e.get("score").asDouble(), a.getScore(), 0.0, ctx + " score");
            assertEquals(e.get("base_score").asDouble(), a.getBaseScore(), 0.0, ctx + " base");
            assertEquals(e.get("favorite_score").asDouble(), a.getFavoriteScore(), 0.0, ctx + " favorite");
            assertEquals(e.get("history_score").asDouble(), a.getHistoryScore(), 0.0, ctx + " history");
            assertEquals(e.get("issue_penalty").asDouble(), a.getIssuePenalty(), 0.0, ctx + " issue");
            assertEquals(e.get("preference_nutrition_mismatch_penalty").asDouble(), a.getPreferenceNutritionMismatchPenalty(), 0.0, ctx + " mismatch");
            assertEquals(e.get("nutrition_confidence").asDouble(), a.getNutritionConfidence(), 0.0, ctx + " nconf");
            assertEquals(e.get("penalty_confidence").asDouble(), a.getPenaltyConfidence(), 0.0, ctx + " pconf");
            assertEquals(e.get("diet_alignment").asDouble(), a.getDietAlignment(), 0.0, ctx + " diet");
            assertEquals(e.get("ingredient_match_ratio").asDouble(), a.getIngredientMatchRatio(), 0.0, ctx + " ingr");
            assertVectorClose(doubles(e.get("dish_vector")), a.getDishVector());
            assertVectorClose(doubles(e.get("user_vector")), a.getUserVector());
        }
    }

    @Test
    void scoringEngineScore_matchesDjango_defaultWeightsBalancedStrong() {
        List<ScoredItem> scored = defaultEngine().score(fixtureDataSource(), 1L, fixtureDishes(),
                List.of("22222222-2222-2222-2222-222222222222", "44444444-4444-4444-4444-444444444444"),
                List.of("ing-a"), "BALANCED", "STRONG", fixtureIssueProfile(), 0.62,
                List.of(0.3, 0.1, 0.6, 0.0012, 0.02));
        assertScored(V.get("score_default"), scored);
    }

    @Test
    void scoringEngineScore_matchesDjango_noPersistedVectorNoDiet() {
        List<ScoredItem> scored = defaultEngine().score(fixtureDataSource(), 1L, fixtureDishes(), List.of(), List.of(),
                "NONE", "NONE", fixtureIssueProfile(), 0.0, List.of());
        assertScored(V.get("score_no_persisted_none_diet"), scored);
    }

    @Test
    void scoringEngineScore_matchesDjango_prioritizeUnorderedLightMedium() {
        ScoringEngine prio = new ScoringEngine(0.03, 0.02, weights(), 0.25, 0.1, List.of(0.3, 0.3, 0.4), 0.05, 0.8, true, CLOCK);
        List<ScoredItem> scored = prio.score(fixtureDataSource(), 1L, fixtureDishes(),
                List.of("11111111-1111-1111-1111-111111111111"), List.of("x"), "LIGHT", "MEDIUM",
                fixtureIssueProfile(), 1.0, List.of(10.0, 1.0, 2.0, 0.1, 0.0));
        assertScored(V.get("score_prioritize_unordered"), scored);
    }

    // ------------------------------------------------------------------ MMR / explain / quotas

    @Test
    void mmrRerank_matchesDjangoOrder() {
        List<ScoredItem> items = new ArrayList<>();
        IN.get("mmr_items").forEach(i -> {
            ScoredItem s = new ScoredItem();
            s.setDishId(str(i.get("id")));
            s.setScore(i.get("score").asDouble());
            s.setDishVector(doubles(i.get("dish_vector")));
            items.add(s);
        });
        for (JsonNode c : V.get("mmr")) {
            List<ScoredItem> r = new MmrReranker(c.get("lambda").asDouble()).rerank(items, c.get("take").asInt());
            assertThat(r.stream().map(ScoredItem::getDishId).toList()).as(c.toString()).isEqualTo(strings(c.get("order")));
        }
    }

    @Test
    void buildReasons_matchesDjango() {
        for (JsonNode c : V.get("explain")) {
            JsonNode in = c.get(0);
            ScoredItem s = new ScoredItem();
            s.setFavoriteScore(in.path("favorite_score").asDouble(0.0));
            s.setHistoryScore(in.path("history_score").asDouble(0.0));
            s.setIngredientMatchRatio(in.path("ingredient_match_ratio").asDouble(0.0));
            s.setIssuePenalty(in.path("issue_penalty").asDouble(0.0));
            s.setPreferenceNutritionMismatchPenalty(in.has("preference_nutrition_mismatch_penalty")
                    ? in.get("preference_nutrition_mismatch_penalty").asDouble() : null);
            s.setNutritionPenalty(in.path("nutrition_penalty").asDouble(0.0));
            s.setDietAlignment(in.path("diet_alignment").asDouble(0.0));
            s.setBaseScore(in.path("base_score").asDouble(0.0));
            assertThat(Explain.buildReasons(s)).as(in.toString()).isEqualTo(strings(c.get(1)));
        }
    }

    @Test
    void candidateSourceQuotas_matchDjango() {
        for (JsonNode c : V.get("quotas")) {
            Map<String, Integer> q = CandidateGenerator.resolveSourceQuotas(c.get("pool").asInt(), c.get("use_ann").asBoolean(),
                    c.get("has_vec").asBoolean());
            Map<String, Integer> expected = new LinkedHashMap<>();
            c.get("quotas").properties().forEach(e -> expected.put(e.getKey(), e.getValue().asInt()));
            assertThat(q).as(c.toString()).isEqualTo(expected);
        }
        // str(float) literal: the value (not the textual format) is what pgvector receives.
        assertThat(VectorMath.parseVectorLiteral(PyMath.vectorLiteral(List.of(1.0, 2.5, 0.1, 1e-05, 123456789.125))))
                .isEqualTo(VectorMath.parseVectorLiteral(V.get("vector_literal").get(0).asString()));
    }

    // ------------------------------------------------------------------ issue profile

    @Test
    void issueSensitivityProfile_matchesDjango() {
        List<RecommendationService.IssueRow> rows = new ArrayList<>();
        IN.get("issue_rows_user").forEach(r -> rows.add(new RecommendationService.IssueRow(str(r.get("issue")),
                dbl(r.get("weight")), instant(r.get("created_at")))));
        RecommendationService.IssueProfile p = RecommendationService.computeIssueProfile(1L, rows, NOW);
        JsonNode e = V.get("issue_profile");
        assertThat(p.issueProfile()).isEqualTo(doubleMap(e.get("profile")));
        assertThat(new ArrayList<>(p.issueProfile().keySet())).isEqualTo(new ArrayList<>(doubleMap(e.get("profile")).keySet()));
        assertEquals(e.get("confidence").asDouble(), p.confidence(), 0.0);
        assertEquals(e.get("data_points").asInt(), p.dataPoints());
        for (JsonNode c : V.get("classify")) {
            assertEquals(str(c.get(1)), RecommendationService.classifyIssue(str(c.get(0))), c.toString());
        }
        assertThat(RecommendationService.computeIssueProfile(1L, List.of(), NOW).dataPoints()).isZero();
    }

    // ------------------------------------------------------------------ vector index math

    @Test
    void dishVectorAggregation_matchesDjango() {
        List<VectorMath.IngredientRow> rows = new ArrayList<>();
        IN.get("vi_rows").forEach(r -> rows.add(new VectorMath.IngredientRow(str(r.get("dish_id")), dbl(r.get("weight")),
                dbl(r.get("protein")), dbl(r.get("lipid")), dbl(r.get("carbohydrate")), dbl(r.get("natri")), dbl(r.get("fiber")),
                dbl(r.get("confidence")))));
        List<VectorMath.DishVectorPayload> payload = VectorMath.aggregateDishVectors(rows);
        JsonNode expected = V.get("dish_vectors");
        assertThat(payload).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(str(expected.get(i).get(0)), payload.get(i).dishId());
            assertThat(payload.get(i).vector()).isEqualTo(doubles(expected.get(i).get(1)));
            assertEquals(expected.get(i).get(2).asDouble(), payload.get(i).confidence(), 0.0);
            // What Django sends to pgvector: its str(float) literal parses to the same doubles as ours.
            assertThat(VectorMath.parseVectorLiteral(PyMath.vectorLiteral(payload.get(i).vector())))
                    .isEqualTo(VectorMath.parseVectorLiteral(V.get("dish_vectors_literals").get(i).asString()));
        }
    }

    @Test
    void userVectorFromHistory_matchesDjango() {
        List<VectorMath.HistoryRow> rows = new ArrayList<>();
        IN.get("hist_rows").forEach(r -> rows.add(new VectorMath.HistoryRow(str(r.get("dish_id")), integer(r.get("quantity")),
                instant(r.get("order__created_at")))));
        Map<String, List<Double>> vectors = new LinkedHashMap<>();
        IN.get("hist_vectors").properties().forEach(e -> vectors.put(e.getKey(), doubles(e.getValue())));
        assertThat(VectorMath.userVectorFromHistory(rows, vectors, 0.03, NOW))
                .isEqualTo(doubles(V.get("user_vector_history").get("vector")));
        assertThat(VectorMath.userVectorFromHistory(rows, vectors, 0.1, NOW)).isEqualTo(doubles(V.get("user_vector_history_l01")));
        for (JsonNode c : V.get("parse_literal")) {
            assertThat(VectorMath.parseVectorLiteral(str(c.get(0)))).as(c.toString()).isEqualTo(doubles(c.get(1)));
        }
    }

    // ------------------------------------------------------------------ daily nutrition

    @Test
    void dailyTargets_matchDjango() {
        for (JsonNode c : V.get("targets")) {
            JsonNode in = c.get("in");
            double[] out = DailyNutritionMath.computeTargets(in.get(3).asDouble(), in.get(4).asDouble(), in.get(5).asDouble(),
                    in.get(0).asString(), in.get(1).asString(), in.get(2).asString());
            assertThat(Arrays.stream(out).boxed().toList()).as(in.toString()).isEqualTo(doubles(c.get("out")));
        }
    }

    private static UserDailyNutrition fixtureDaily() {
        JsonNode d = IN.get("daily_obj");
        return UserDailyNutrition.builder().date(LocalDate.parse(d.get("date").asString()))
                .bmrKcal(d.get("bmr_kcal").asDouble()).tdeeKcal(d.get("tdee_kcal").asDouble())
                .targetProteinG(d.get("target_protein_g").asDouble()).targetLipidG(d.get("target_lipid_g").asDouble())
                .targetCarbG(d.get("target_carb_g").asDouble()).targetSodiumMg(d.get("target_sodium_mg").asDouble())
                .targetFiberG(d.get("target_fiber_g").asDouble())
                .consumedProteinG(d.get("consumed_protein_g").asDouble()).consumedLipidG(d.get("consumed_lipid_g").asDouble())
                .consumedCarbG(d.get("consumed_carb_g").asDouble()).consumedSodiumMg(d.get("consumed_sodium_mg").asDouble())
                .consumedFiberG(d.get("consumed_fiber_g").asDouble()).build();
    }

    private static List<DailyNutritionMath.LogValues> fixtureLogs() {
        List<DailyNutritionMath.LogValues> logs = new ArrayList<>();
        IN.get("logs").forEach(l -> logs.add(new DailyNutritionMath.LogValues(
                l.get("nutrition_protein_g").asDouble(0.0), l.get("nutrition_lipid_g").isNull() ? 0.0 : l.get("nutrition_lipid_g").asDouble(),
                l.get("nutrition_carb_g").asDouble(), l.get("nutrition_sodium_mg").asDouble(), l.get("nutrition_fiber_g").asDouble(),
                l.get("confidence_parse").asDouble(), l.get("confidence_source").asDouble())));
        return logs;
    }

    private static DailyNutritionMath.Summary fixtureSummary() {
        JsonNode s = V.get("summary");
        return new DailyNutritionMath.Summary(s.get("date").asString(), s.get("bmr_kcal").asDouble(), s.get("tdee_kcal").asDouble(),
                doubleMap(s.get("target")), doubleMap(s.get("consumed")), doubleMap(s.get("remaining")));
    }

    @Test
    void uncertaintyAndSummary_matchDjango() {
        Map<String, Double> unc = DailyNutritionMath.consumedUncertainty(fixtureLogs());
        assertThat(unc).isEqualTo(doubleMap(V.get("uncertainty")));
        DailyNutritionMath.Summary s = DailyNutritionMath.buildSummary(fixtureDaily(), unc);
        DailyNutritionMath.Summary e = fixtureSummary();
        assertEquals(e.date(), s.date());
        assertEquals(e.bmrKcal(), s.bmrKcal(), 0.0);
        assertEquals(e.tdeeKcal(), s.tdeeKcal(), 0.0);
        assertThat(s.target()).isEqualTo(e.target());
        assertThat(s.consumed()).isEqualTo(e.consumed());
        assertThat(s.remaining()).isEqualTo(e.remaining());
    }

    @Test
    void balancedFitFunctions_matchDjango() {
        Map<String, Double> target = doubleMap(V.get("summary").get("target"));
        for (JsonNode c : V.get("nutrition_fit")) {
            Map<String, Double> rem = doubleMap(c.get("remaining"));
            Map<String, Double> dish = doubleMap(c.get("dish"));
            String ctx = c.get("remaining") + " / " + c.get("dish");
            assertEquals(c.get("macro").asDouble(), DailyNutritionMath.macroMatchScore(rem, dish), 0.0, ctx);
            assertEquals(c.get("sodium").asDouble(), DailyNutritionMath.sodiumPenalty(rem, dish), 0.0, ctx);
            double servings = DailyNutritionMath.suggestServings(rem, dish);
            assertEquals(c.get("servings").asDouble(), servings, 0.0, ctx);
            assertEquals(c.get("portion").asDouble(), DailyNutritionMath.validateMealPortion(servings, dish, target), 0.0, ctx);
            assertEquals(c.get("portion_2_4").asDouble(), DailyNutritionMath.validateMealPortion(2.4, dish, target), 0.0, ctx);
            assertThat(DailyNutritionMath.remainingReasons(rem)).isEqualTo(strings(c.get("reasons")));
        }
    }

    @Test
    void sourceConfidence_matchesDjango_incl_sequenceMatcher() {
        for (JsonNode c : V.get("source_conf")) {
            Map<String, Double> nut = doubleMap(c.get("nut"));
            assertEquals(c.get("sim").asDouble(), DailyNutritionMath.nameSimilarity(str(c.get("a")), str(c.get("b"))), 0.0, c.toString());
            assertEquals(c.get("complete").asDouble(), DailyNutritionMath.nutritionCompleteness(nut), 0.0);
            assertEquals(c.get("conf").asDouble(),
                    DailyNutritionMath.computeSourceConfidence(str(c.get("a")), str(c.get("b")), nut, dbl(c.get("q"))), 0.0);
        }
    }

    @Test
    void mealTextParsers_matchDjango() {
        for (JsonNode c : V.get("heuristic")) {
            List<List<Object>> actual = DailyNutritionMath.heuristicParse(c.get("text").asString()).stream()
                    .map(m -> List.<Object>of(m.name(), m.quantityMultiplier(), m.confidenceParse())).toList();
            List<List<Object>> expected = new ArrayList<>();
            c.get("meals").forEach(m -> expected.add(List.of(m.get(0).asString(), m.get(1).asDouble(), m.get(2).asDouble())));
            assertThat(actual).as(c.get("text").asString()).isEqualTo(expected);
        }
        Object rows = M.readValue("""
                [{"name": " Phở ", "quantity_multiplier": 1.5, "confidence_parse": 0.9},
                 {"name": "Cơm", "quantity_multiplier": 9, "confidence": 0.4},
                 {"name": "Trà", "quantity_multiplier": 0, "confidence_parse": 0},
                 {"name": "Kem", "quantity_multiplier": 0.1, "confidence_parse": 1.5},
                 {"name": ""}, "junk", {"name": null}]
                """, Object.class);
        List<List<Object>> actual = DailyNutritionMath.normalizeParsedRows(rows).stream()
                .map(m -> List.<Object>of(m.name(), m.quantityMultiplier(), m.confidenceParse())).toList();
        List<List<Object>> expected = new ArrayList<>();
        V.get("normalize_rows").forEach(m -> expected.add(List.of(m.get(0).asString(), m.get(1).asDouble(), m.get(2).asDouble())));
        assertThat(actual).isEqualTo(expected);

        for (JsonNode c : V.get("json_safe")) {
            Object parsed = DailyNutritionMath.parseJsonSafely(DailyNutritionMath.extractJson(c.get(0).asString()));
            JsonNode actualNode = parsed == null ? tools.jackson.databind.node.NullNode.getInstance() : (JsonNode) M.valueToTree(parsed);
            assertThat(actualNode).as(c.get(0).asString()).isEqualTo(c.get(1));
        }
        for (JsonNode c : V.get("meal_time")) {
            if (c.get(0).isString()) {
                assertEquals(c.get(1).asString(), DailyNutritionMath.inferMealTime(null).name());
            } else {
                assertEquals(c.get(1).asString(), DailyNutritionMath.inferMealTime(LocalTime.of(c.get(0).asInt(), 30)).name());
            }
        }
    }

    private static List<DailyNutritionMath.DishIngredientRow> fixtureDishIngredientRows() {
        List<DailyNutritionMath.DishIngredientRow> rows = new ArrayList<>();
        IN.get("dnm_rows").forEach(r -> rows.add(new DailyNutritionMath.DishIngredientRow(str(r.get("dish_id")),
                dbl(r.get("protein")), dbl(r.get("lipid")), dbl(r.get("carbohydrate")), dbl(r.get("natri")), dbl(r.get("fiber")),
                integer(r.get("dish__serving_size")), dbl(r.get("weight")), dbl(r.get("confidence")))));
        return rows;
    }

    @Test
    void dishNutritionAndQualityMaps_andRecipeMath_matchDjango() {
        Map<String, Map<String, Double>> nm = DailyNutritionMath.dishNutritionMap(fixtureDishIngredientRows());
        Map<String, Map<String, Double>> expected = new LinkedHashMap<>();
        V.get("dish_nutrition_map").properties().forEach(e -> expected.put(e.getKey(), doubleMap(e.getValue())));
        assertThat(nm).isEqualTo(expected);
        List<String> ids = List.of("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333");
        assertThat(DailyNutritionMath.dishQualityMap(fixtureDishIngredientRows(), ids)).isEqualTo(doubleMap(V.get("dish_quality_map")));

        String ingA = "aaaaaaaa-0000-0000-0000-000000000001";
        String ingB = "bbbbbbbb-0000-0000-0000-000000000002";
        DailyNutritionMath.MappedRecipe recipe = DailyNutritionMath.normalizeAndMapRecipe(json("""
                {"dish": "phở bò", "confidence": 0.78, "ingredients": [
                  {"name": "banh pho", "weight_g": 200}, {"name": "thit bo", "weight": 100.5}, {"name": "hanh", "weight_g": 10},
                  {"name": "", "weight_g": 5}, {"name": "x", "weight_g": 0}, "junk", {"name": "muoi bo", "weight_g": 1.23456}]}
                """), name -> name.contains("bo") ? ingA : (name.contains("pho") ? ingB : null));
        JsonNode mapped = V.get("recipe_mapped");
        assertEquals(mapped.get("conf").asDouble(), recipe.confidence(), 0.0);
        assertThat((JsonNode) M.valueToTree(recipe.ingredients().stream().map(DailyNutritionMath.MappedIngredient::asMap).toList()))
                .isEqualTo(mapped.get("mapped"));
        Map<String, DailyNutritionMath.IngredientNutrition> ings = new HashMap<>();
        IN.get("recipe_ingredients").properties().forEach(e -> ings.put(e.getKey(), new DailyNutritionMath.IngredientNutrition(
                dbl(e.getValue().get("protein")), dbl(e.getValue().get("lipid")), dbl(e.getValue().get("carbohydrate")),
                dbl(e.getValue().get("natri")), dbl(e.getValue().get("fiber")))));
        assertThat(DailyNutritionMath.computeRecipeNutrition(recipe.ingredients(), ings)).isEqualTo(doubleMap(V.get("recipe_nutrition")));
        assertEquals(V.get("recipe_conf_clamp").asDouble(),
                DailyNutritionMath.normalizeAndMapRecipe(json("{\"confidence\": 7, \"ingredients\": []}"), n -> null).confidence(), 0.0);
    }

    private void assertBalanced(JsonNode expected, List<DailyNutritionMath.BalancedItem> actual) {
        JsonNode items = expected.get("items");
        assertThat(actual).hasSize(items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode e = items.get(i);
            DailyNutritionMath.BalancedItem a = actual.get(i);
            assertEquals(e.get("rank_position").asInt(), a.rankPosition());
            assertEquals(str(e.get("dish_uid")), a.dishUid());
            assertEquals(str(e.get("dish_name")), a.dishName());
            assertEquals(str(e.get("public_url")), a.publicUrl());
            assertEquals(e.get("price").asDouble(), a.price(), 0.0);
            assertEquals(e.get("avg_rating").asDouble(), a.avgRating(), 0.0);
            assertEquals(e.get("base_recommendation_score").asDouble(), a.baseRecommendationScore(), 0.0);
            assertEquals(e.get("macro_match_score").asDouble(), a.macroMatchScore(), 0.0, "macro " + i);
            assertEquals(e.get("final_score").asDouble(), a.finalScore(), 0.0, "final " + i);
            assertEquals(e.get("suggested_servings").asDouble(), a.suggestedServings(), 0.0, "servings " + i);
            assertThat(a.nutritionImpact()).isEqualTo(doubleMap(e.get("nutrition_impact")));
            assertThat(a.reasons()).isEqualTo(strings(e.get("reasons")));
        }
    }

    @Test
    void balancedRecommendationRanking_matchesDjango() {
        List<DailyNutritionMath.FeedItem> feed = new ArrayList<>();
        IN.get("base_items").forEach(i -> feed.add(new DailyNutritionMath.FeedItem(str(i.get("dish_uid")), str(i.get("dish_name")),
                str(i.get("public_url")), i.get("price").asDouble(), i.get("avg_rating").asDouble(), i.get("score").asDouble(),
                strings(i.get("reasons")))));
        Map<String, Map<String, Double>> bn = new HashMap<>();
        IN.get("bn_map").properties().forEach(e -> bn.put(e.getKey(), doubleMap(e.getValue())));
        Map<String, Double> bq = doubleMap(IN.get("bq_map"));
        java.util.function.Function<List<String>, Map<String, Map<String, Double>>> nf = ids -> {
            Map<String, Map<String, Double>> out = new HashMap<>();
            ids.forEach(id -> {
                if (bn.containsKey(id)) {
                    out.put(id, bn.get(id));
                }
            });
            return out;
        };
        java.util.function.Function<List<String>, Map<String, Double>> qf = ids -> {
            Map<String, Double> out = new HashMap<>();
            ids.forEach(id -> out.put(id, bq.getOrDefault(id, 0.9)));
            return out;
        };
        Set<String> logged = Set.of(feed.get(0).dishUid());
        assertBalanced(V.get("balanced_limit3"), DailyNutritionMath.rankBalanced(feed, logged, 3, fixtureSummary(), nf, qf));
        assertBalanced(V.get("balanced_limit20"), DailyNutritionMath.rankBalanced(feed, logged, 20, fixtureSummary(), nf, qf));
        assertBalanced(V.get("balanced_limit2_nolog"), DailyNutritionMath.rankBalanced(feed, Set.of(), 2, fixtureSummary(), nf, qf));
        // Django's pipeline pool: max(60, limit * 5), clamped to 100 inside the pipeline.
        List<Integer> limits = new ArrayList<>();
        V.get("balanced_pipeline_calls").forEach(c -> limits.add(c.get("limit").asInt()));
        assertThat(limits).containsExactly(60, 100, 60);
    }

    // ------------------------------------------------------------------ find_better_dish_for_issue

    @Test
    void betterDishForIssueScoring_matchesDjango() {
        List<String> candidates = strings(IN.get("better_candidates"));
        String source = candidates.get(0);
        List<String> ids = new ArrayList<>(candidates);
        ids.remove(source); // candidate_ids.discard(str(dish_uid))
        Map<String, Integer> issueMap = new HashMap<>();
        IN.get("better_issue_counts").forEach(r -> issueMap.put(str(r.get("dish__uid")), r.get("count").asInt()));
        Map<String, RecommendationService.RatingStats> ratings = new HashMap<>();
        IN.get("better_rating_stats").forEach(r -> ratings.put(str(r.get("dish_uid")),
                new RecommendationService.RatingStats(r.get("avg_rating").asDouble(), r.get("total_reviews").asInt())));
        List<RecommendationService.BetterScore> top = RecommendationService.scoreBetterCandidates(ids, issueMap, ratings).subList(0, 5);
        JsonNode expected = V.get("better_for_issue").get("items");
        assertThat(top).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            assertEquals(str(e.get("dish_uid")), top.get(i).dishUid());
            assertEquals(e.get("avg_rating").asDouble(), top.get(i).avgRating(), 0.0);
            assertEquals(e.get("total_reviews").asInt(), top.get(i).totalReviews());
            assertEquals(e.get("issue_rate").asDouble(), top.get(i).issueRate(), 0.0);
            assertEquals(e.get("score").asDouble(), top.get(i).score(), 0.0);
        }
        assertEquals("mặn", V.get("better_for_issue").get("issue").asString());
    }

    // ------------------------------------------------------------------ rounding / libm

    @Test
    void pyRound_isPythonsRound_andDishPyRoundNowMatchesIt() {
        for (JsonNode c : V.get("pyround")) {
            double expected = c.get(2).asDouble();
            double actual = PyMath.round(c.get(0).asDouble(), c.get(1).asInt());
            assertEquals(expected, actual, 0.0, c.toString());
            assertEquals(1.0 / expected > 0, 1.0 / actual > 0, "sign of zero " + c);
        }
        // dish's DishNutritionService.pyRound used to round BigDecimal.valueOf(x) (the shortest
        // decimal STRING of the double) instead of the exact binary value, diverging from Python's
        // round() on near-ties (fixed in a dedicated dish pass, PROGRESS.md). It now delegates to
        // this same PyMath.round, so the two agree exactly, including on those near-ties.
        assertEquals(2.67, DishNutritionService.pyRound(2.675, 2), 0.0);
        assertEquals(2.67, PyMath.round(2.675, 2), 0.0);
        assertEquals(0.001, DishNutritionService.pyRound(0.0005, 3), 0.0);
        assertEquals(0.001, PyMath.round(0.0005, 3), 0.0);
    }

    @Test
    void mathExp_agreesWithPythonsLibmToTheLastUlp() {
        for (JsonNode c : V.get("exp_samples")) {
            double expected = c.get(1).asDouble();
            assertEquals(expected, Math.exp(c.get(0).asDouble()), Math.ulp(expected), c.toString());
        }
    }
}
