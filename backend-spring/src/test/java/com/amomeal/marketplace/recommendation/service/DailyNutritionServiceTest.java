package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.service.OrderService;
import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import com.amomeal.marketplace.recommendation.config.RecommendationProperties;
import com.amomeal.marketplace.recommendation.dto.*;
import com.amomeal.marketplace.recommendation.entity.DishTranslationMapping;
import com.amomeal.marketplace.recommendation.exception.DailyNutritionProfileNotFoundException;
import com.amomeal.marketplace.recommendation.repository.DailyMealLogRepository;
import com.amomeal.marketplace.recommendation.repository.DishRecipeSnapshotRepository;
import com.amomeal.marketplace.recommendation.repository.DishTranslationMappingRepository;
import com.amomeal.marketplace.recommendation.support.RecommendationIntegrationTestBase;
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Daily nutrition on real Postgres with a FAKE Gemini ({@link com.amomeal.marketplace.recommendation.support.FakeGeminiClient}).
 *
 * <p>Tiers 2-4 of parse-meal (translation mapping / USDA ingredient / Gemini recipe) are only
 * reachable when dish search returns nothing — and, faithfully to Django, dish search returns the
 * best fuzzy dish whenever ANY live dish exists (dead FUZZY_THRESHOLD, see PROGRESS.md). Those tests
 * therefore soft-delete every dish inside a {@code @Transactional} test that rolls back.
 */
class DailyNutritionServiceTest extends RecommendationIntegrationTestBase {

    @Autowired RecommendationService service;
    @Autowired DailyNutritionService dailyNutritionService;
    @Autowired DishTranslationMappingRepository mappingRepository;
    @Autowired DishRecipeSnapshotRepository snapshotRepository;
    @Autowired DailyMealLogRepository mealLogRepository;
    @Autowired GeminiProperties geminiProperties;
    @Autowired RecommendationProperties recommendationProperties;
    @Autowired OrderService orderService;

    @BeforeEach
    void resetGemini() {
        gemini.reset();
        geminiProperties.setApiKey("test-gemini-key");
        geminiProperties.setPreserveRecipeTimeoutBug(false);
        recommendationProperties.setDishMatchThreshold(0.6);
        recommendationProperties.setPreserveDishMatchNoThreshold(false);
    }

    @Test
    void initSummaryProfile_matchTheDjangoFormulas_andPartialUpdateRecomputes() throws Exception {
        var customer = register("dncust", UserRole.CUSTOMER);
        assertThatThrownBy(() -> service.getDailyNutritionProfile(customer.id()))
                .isInstanceOf(DailyNutritionProfileNotFoundException.class);

        DailyNutritionSummaryResponse s = service.initDailyNutrition(customer.id(), 30, "male", 175, 75, "moderate", "maintain");
        double[] t = DailyNutritionMath.computeTargets(30, 75, 175, "MALE", "MODERATE", "MAINTAIN");
        assertEquals(t[0], s.bmrKcal(), 0.0);
        assertEquals(t[1], s.tdeeKcal(), 0.0);
        assertThat(s.target()).isEqualTo(new NutritionValuesResponse(t[2], t[3], t[4], t[5], t[6]));
        assertThat(s.consumed()).isEqualTo(new NutritionValuesResponse(0, 0, 0, 0, 0));
        assertThat(s.remaining()).isEqualTo(s.target());

        DailyNutritionProfileResponse p = service.updateDailyNutritionProfile(customer.id(), null, null, null, 80.0, null, "GAIN");
        assertThat(p.gender()).isEqualTo("MALE");
        assertThat(p.weightKg()).isEqualTo(80.0);
        assertThat(p.goal()).isEqualTo("GAIN");
        double[] t2 = DailyNutritionMath.computeTargets(30, 80, 175, "MALE", "MODERATE", "GAIN");
        assertEquals(t2[1], p.tdeeKcal(), 0.0);
        assertEquals(t2[2], p.target().proteinG(), 0.0);
        assertThat(service.getDailyNutritionProfile(customer.id()).age()).isEqualTo(30);
    }

    @Test
    void completedAppOrders_areSyncedIdempotently_withMealTimeFromDeliveryTime_andDeletedLogsStayDeleted() throws Exception {
        var chef = chef("dnchef");
        var customer = register("dncust2", UserRole.CUSTOMER);
        Dish d = dish(chef, unique("Bún bò Huế"), 4.2);
        line(d, null, 300, 30, 12, 60, 1500, 3, 0.9);
        order(customer, d, 2, OrderStatus.COMPLETED, LocalTime.of(8, 15));
        order(customer, d, 1, OrderStatus.PENDING, LocalTime.of(19, 0)); // not COMPLETED -> ignored

        DailyMealLogListResponse logs = service.getDailyMealLogs(customer.id());
        assertThat(logs.items()).hasSize(1);
        DailyMealLogItemResponse log = logs.items().get(0);
        assertThat(log.source()).isEqualTo("APP");
        assertThat(log.mealTime()).isEqualTo("BREAKFAST");
        assertThat(log.dishUid()).isEqualTo(d.getUid().toString());
        assertThat(log.quantityMultiplier()).isEqualTo(2.0);
        assertThat(log.nutritionProteinG()).isEqualTo(60.0);
        assertThat(log.confidenceSource()).isEqualTo(0.9);
        assertThat(log.rawPayload()).containsKeys("order_uid", "order_item_id");
        assertThat(log.price()).isEqualTo(50000.0);
        assertThat(logs.summary().consumed().proteinG()).isEqualTo(60.0);

        // idempotent
        assertThat(service.getDailyMealLogs(customer.id()).items()).hasSize(1);

        // PATCH quantity scales nutrition; DELETE removes from totals and is NOT re-synced
        DailyMealLogListResponse updated = service.updateDailyMealLog(customer.id(), log.uid(), "Bún bò nửa tô", "LUNCH", 1.0, null);
        assertThat(updated.items().get(0).nutritionProteinG()).isEqualTo(30.0);
        assertThat(updated.items().get(0).mealTime()).isEqualTo("LUNCH");
        DailyMealLogListResponse deleted = service.deleteDailyMealLog(customer.id(), log.uid());
        assertThat(deleted.items()).isEmpty();
        assertThat(deleted.summary().consumed().proteinG()).isEqualTo(0.0);
        assertThat(service.getDailyNutritionSummary(customer.id()).consumed().proteinG()).isEqualTo(0.0);

        assertThatThrownBy(() -> service.deleteDailyMealLog(customer.id(), log.uid()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Daily meal log not found");
    }

    @Test
    void orderCompletion_throughOrderService_syncsMealLog_andRefreshesUserVector() throws Exception {
        var chef = chef("occhef");
        var customer = register("occust", UserRole.CUSTOMER);
        Dish d = dish(chef, unique("Cơm gà xối mỡ"), 4.0);
        line(d, null, 250, 25, 20, 70, 900, 2, 1.0);
        Order order = order(customer, d, 1, OrderStatus.DELIVERING, LocalTime.of(12, 30));

        orderService.completeOrderWithRelease(order.getUid());

        // aspect: transaction.on_commit(sync_order_meal_logs) — the APP log exists without any daily call
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM daily_meal_log l JOIN user_daily_nutrition d ON d.uid = l.daily_nutrition_uid
                WHERE d.user_id = ? AND l.source = 'APP' AND l.meal_time = 'LUNCH'
                """, Long.class, customer.id());
        assertThat(count).isEqualTo(1L);
        // order signal: refresh_user_vector (history, confidence 0.8)
        assertEquals(0.8, jdbc.queryForObject("SELECT confidence FROM recommendation_user_vector_index WHERE user_id = ?",
                Double.class, customer.id()), 1e-6);
    }

    @Test
    void parseMeal_geminiParsesText_andDishTierResolvesIt() throws Exception {
        var chef = chef("pmchef");
        var customer = register("pmcust", UserRole.CUSTOMER);
        String name = unique("Phở bò tái lăn");
        Dish d = dish(chef, name, 4.8);
        line(d, null, 400, 40, 10, 80, 2000, 4, 1.0);
        gemini.respondWith(prompt -> "```json\n{\"meals\": [{\"name\": \"" + name
                + "\", \"quantity_multiplier\": 1.5, \"confidence_parse\": 0.9}]}\n```");

        DailyNutritionMealParseResponse r = service.parseDailyMeal(customer.id(), "sáng ăn " + name, "breakfast");
        assertThat(r.parsedCount()).isEqualTo(1);
        assertThat(r.unresolvedMeals()).isEmpty();
        assertThat(gemini.calls()).hasSize(1);
        assertThat(gemini.calls().get(0).model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(gemini.calls().get(0).timeout()).isNull(); // Django passes no timeout to the parser
        assertThat(gemini.calls().get(0).prompt()).contains("You are a strict JSON generator.").contains("sáng ăn " + name);

        DailyMealLogItemResponse log = service.getDailyMealLogs(customer.id()).items().get(0);
        assertThat(log.source()).isEqualTo("PARSED");
        assertThat(log.mealTime()).isEqualTo("BREAKFAST");
        assertThat(log.dishUid()).isEqualTo(d.getUid().toString());
        assertThat(log.nutritionProteinG()).isEqualTo(60.0);
        assertThat(log.confidenceParse()).isEqualTo(0.9);
        assertThat(log.rawPayload()).isEqualTo(Map.of("resolver", "dish_db"));
        assertThat(log.confidenceSource()).isEqualTo(
                DailyNutritionMath.computeSourceConfidence(name, name, Map.of("protein_g", 40.0, "lipid_g", 10.0,
                        "carb_g", 80.0, "sodium_mg", 2000.0, "fiber_g", 4.0), 1.0));
    }

    @Test
    void parseMeal_geminiFailureOrNoKey_fallsBackToTheHeuristicParser() throws Exception {
        var chef = chef("hchef");
        var customer = register("hcust", UserRole.CUSTOMER);
        dish(chef, unique("Bánh mì"), 4.0);
        gemini.respondWith(prompt -> {
            throw new IllegalStateException("gemini down");
        });
        // Heuristic split gives two meals: "bánh mì nhiều" is a good fuzzy match for the dish above
        // (search_score comfortably >= the 0.6 threshold) and resolves via dish_db; "trà đá" is
        // unrelated (search_score well under 0.6) and now correctly falls through every tier
        // (no mapping/ingredient matches it either, and Gemini fails here too) instead of Django's
        // bug of matching whatever dish happens to exist (CLAUDE.md §0.6 / PROGRESS.md).
        DailyNutritionMealParseResponse r = service.parseDailyMeal(customer.id(), "bánh mì nhiều, trà đá", "UNKNOWN");
        assertThat(r.parsedCount()).isEqualTo(1);
        assertThat(r.unresolvedMeals()).containsExactly("tra da");
        assertThat(gemini.calls()).hasSize(2); // meal-parser attempt (fails) + recipe-generator attempt for "tra da" (fails)

        gemini.reset();
        geminiProperties.setApiKey("");
        assertThat(dailyNutritionService.parseMealText("cơm tấm to lớn và canh"))
                .containsExactly(new DailyNutritionMath.ParsedMeal("com tam to lon", 1.5, 0.65),
                        new DailyNutritionMath.ParsedMeal("canh", 1.0, 0.65));
        assertThat(gemini.calls()).isEmpty();
    }

    /**
     * 🔴 Dedicated coverage for the user-decided fix (CLAUDE.md §0.6, PROGRESS.md): {@code matchDish}
     * now gates the #1 dish-search hit on its own {@code search_score} — the same weighted
     * fuzzy/exact + rating + popularity score {@code DishSearchService} already computes, not a new
     * metric — instead of taking it unconditionally like Django. Ratings are pinned at 0 so
     * search_score reduces to exactly {@code 0.8 * fuzzyRatio} (the fuzzy_name weight), letting the
     * boundary be checked precisely: "bun bo" vs "banh bao" -> ratio 0.714... -> 0.571 (below 0.6,
     * falls through); "bun bo" vs "bun bo hue" -> ratio 0.75 exactly -> 0.6 exactly (meets ">=
     * 0.6", matches) — both ratios verified against Python's real difflib.SequenceMatcher.
     */
    @Test
    @Transactional
    void matchDish_gatesOnSearchScore_unrelatedFallsThrough_closeNameMatches_flagRestoresOldBehavior() throws Exception {
        var chef = chef("mdchef");
        jdbc.update("UPDATE dish SET deleted = TRUE"); // isolate the candidate set; rolled back with the test

        dish(chef, "Bánh bao", 0.0);
        assertThat(dailyNutritionService.matchDish("bún bò")).isNull();

        Dish close = dish(chef, "Bún bò Huế", 0.0);
        Dish matched = dailyNutritionService.matchDish("bún bò");
        assertThat(matched).isNotNull();
        assertThat(matched.getUid()).isEqualTo(close.getUid());

        // flag=true reproduces Django's bug: take the top hit regardless of search_score, so even
        // the unrelated-only case above would now match.
        recommendationProperties.setPreserveDishMatchNoThreshold(true);
        jdbc.update("UPDATE dish SET deleted = TRUE");
        Dish onlyUnrelated = dish(chef, "Bánh bao 2", 0.0);
        Dish forced = dailyNutritionService.matchDish("bún bò");
        assertThat(forced).isNotNull();
        assertThat(forced.getUid()).isEqualTo(onlyUnrelated.getUid());
    }

    @Test
    @Transactional
    void parseMeal_lowerTiers_mappingUsdaIngredientAndGeminiRecipe_whenNoDishMatches() throws Exception {
        var customer = register("tiercust", UserRole.CUSTOMER);
        Ingredient beef = ingredient("thit bo tuoi");
        beef.setProtein(26.0);
        beef.setLipid(15.0);
        beef.setNatri(72.0);
        ingredientRepository.save(beef);
        String beefName = beef.getNameNoAccent();
        Map<String, Object> nutrition = new LinkedHashMap<>();
        nutrition.put("protein_g", 12.0);
        nutrition.put("lipid_g", 8.0);
        nutrition.put("carb_g", 30.0);
        nutrition.put("sodium_mg", 400.0);
        nutrition.put("fiber_g", 2.0);
        String mappingName = unique("bánh xèo miền tây");
        mappingRepository.save(DishTranslationMapping.builder().vietnameseName(mappingName)
                .nutritionPerServing(nutrition).usdaConfidence(0.85).build());
        jdbc.update("UPDATE dish SET deleted = TRUE"); // rolled back with the test

        gemini.respondWith(prompt -> {
            if (prompt.contains("strict JSON generator")) {
                return "{\"meals\": [{\"name\": \"" + mappingName + "\"}, {\"name\": \"" + beefName + "\"},"
                        + " {\"name\": \"món lạ quê nhà\", \"quantity_multiplier\": 2}]}";
            }
            return "Sure! {\"dish\": \"món lạ\", \"confidence\": 0.7, \"ingredients\": ["
                    + "{\"name\": \"" + beefName + "\", \"weight_g\": 200}, {\"name\": \"rau không tên\", \"weight_g\": 50}]}";
        });

        DailyNutritionMealParseResponse r = service.parseDailyMeal(customer.id(), "whatever", "DINNER");
        assertThat(r.parsedCount()).isEqualTo(3);
        assertThat(r.unresolvedMeals()).isEmpty();
        List<DailyMealLogItemResponse> logs = service.getDailyMealLogs(customer.id()).items();
        assertThat(logs).extracting(DailyMealLogItemResponse::source).containsExactly("USDA", "USDA", "PARSED");
        assertThat(logs.get(0).rawPayload()).containsEntry("resolver", "usda_mapping");
        assertThat(logs.get(0).nutritionCarbG()).isEqualTo(30.0);
        assertThat(logs.get(1).rawPayload()).containsEntry("resolver", "usda_ingredient");
        assertThat(logs.get(1).nutritionProteinG()).isEqualTo(26.0);
        DailyMealLogItemResponse recipeLog = logs.get(2);
        assertThat(recipeLog.rawPayload()).containsEntry("resolver", "gemini_generated");
        assertThat(recipeLog.quantityMultiplier()).isEqualTo(2.0);
        assertThat(recipeLog.nutritionProteinG()).isEqualTo(2 * 52.0); // 26 g/100 g x 200 g, x2 servings
        assertThat(recipeLog.confidenceSource()).isEqualTo(0.7);
        assertThat(snapshotRepository.findAll()).anyMatch(s -> s.getDishName().equals("món lạ quê nhà")
                && s.getSource().equals("GEMINI") && s.getIngredients().size() == 2);
        // the recipe call carries Django's intended 3 s timeout
        assertThat(gemini.calls()).anyMatch(c -> c.prompt().contains("Dish: món lạ quê nhà")
                && c.timeout() != null && c.timeout().toMillis() == 3000);

        // preserve-bug flag: Django's generate_content(timeout=...) TypeError => recipe tier always None
        geminiProperties.setPreserveRecipeTimeoutBug(true);
        DailyNutritionMealParseResponse preserved = service.parseDailyMeal(customer.id(), "again", "DINNER");
        assertThat(preserved.unresolvedMeals()).containsExactly("món lạ quê nhà");
        assertThat(preserved.parsedCount()).isEqualTo(2);
    }

    @Test
    void balancedRecommendations_wrapThePipeline_andSkipDishesLoggedToday() throws Exception {
        var chef = chef("brchef");
        var customer = register("brcust", UserRole.CUSTOMER);
        Dish eaten = dish(chef, unique("Gỏi cuốn"), 4.5);
        line(eaten, null, 150, 12, 4, 25, 300, 2, 1.0);
        for (int i = 0; i < 6; i++) {
            Dish d = dish(chef, unique("Món cân bằng " + i), 4.0);
            line(d, null, 200, 20 + i, 8, 50, 600, 3, 0.9);
        }
        order(customer, eaten, 1, OrderStatus.COMPLETED, LocalTime.NOON);
        service.initDailyNutrition(customer.id(), 25, "FEMALE", 160, 55, "LIGHT", "LOSE");

        DailyNutritionRecommendationResponse r = service.getDailyBalancedRecommendations(customer.id(), 5);
        assertThat(r.items()).hasSize(5);
        assertThat(r.items()).noneMatch(i -> i.dishUid().equals(eaten.getUid().toString()));
        assertThat(r.summary().consumed().proteinG()).isEqualTo(12.0);
        r.items().forEach(i -> {
            assertThat(i.finalScore()).isBetween(0.0, 1.0);
            assertThat(i.suggestedServings()).isBetween(0.5, 2.5);
            assertThat(i.reasons()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        });
    }
}
