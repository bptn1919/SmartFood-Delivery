package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.dto.DishSearchResponse;
import com.amomeal.marketplace.dish.dto.DishSearchResultResponse;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.amomeal.marketplace.dish.service.DishSearchService;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.recommendation.config.GeminiProperties;
import com.amomeal.marketplace.recommendation.config.RecommendationProperties;
import com.amomeal.marketplace.recommendation.dto.*;
import com.amomeal.marketplace.recommendation.entity.*;
import com.amomeal.marketplace.recommendation.exception.DailyNutritionProfileNotFoundException;
import com.amomeal.marketplace.recommendation.repository.*;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.amomeal.marketplace.recommendation.service.DailyNutritionMath.*;

/**
 * 1:1 port of ../../backend/recommendation/services/daily_nutrition.py::DailyNutritionService —
 * per-day nutrition profile (BMR/TDEE/targets), meal logging (app orders synced idempotently,
 * free text resolved through dish search → translation mapping → USDA ingredient → Gemini
 * recipe), and the balanced recommendations wrapper around the pipeline. All pure math lives in
 * {@link DailyNutritionMath} (pinned against Python).
 *
 * <p>Transactions mirror Django: {@code parse_outside_meals}, {@code get_daily_meal_logs},
 * {@code update/delete_daily_meal_log}, {@code sync_order_meal_logs} are {@code @transaction.atomic}
 * there; the others run in autocommit — here each of those runs in its own transaction except
 * {@link #getBalancedRecommendations}, which keeps the pipeline call outside any transaction.
 *
 * <p>Gemini: the parser is tried only when {@code GEMINI_API_KEY} is set (else Django returns []
 * immediately); every failure falls back (external AI parser → heuristic). See
 * {@link #callGeminiRecipeGenerator} for the recipe tier and its preserve-bug flag.
 */
@Slf4j
@Service
public class DailyNutritionService {

    private final UserDailyNutritionRepository dailyRepository;
    private final DailyMealLogRepository mealLogRepository;
    private final DishTranslationMappingRepository mappingRepository;
    private final DishRecipeSnapshotRepository snapshotRepository;
    private final RecommendationDishRepository dishRepository;
    private final DishSearchService dishSearchService;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final GeminiClient geminiClient;
    private final GeminiProperties gemini;
    private final RecommendationProperties config;
    private final ExternalMealParserClient externalParser;
    private final RecommendationPipelineService pipeline;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DailyNutritionService(UserDailyNutritionRepository dailyRepository, DailyMealLogRepository mealLogRepository,
                                 DishTranslationMappingRepository mappingRepository,
                                 DishRecipeSnapshotRepository snapshotRepository,
                                 RecommendationDishRepository dishRepository, DishSearchService dishSearchService,
                                 JdbcTemplate jdbc, EntityManager entityManager, GeminiClient geminiClient,
                                 GeminiProperties gemini, RecommendationProperties config,
                                 ExternalMealParserClient externalParser,
                                 @Lazy RecommendationPipelineService pipeline, PlatformTransactionManager txManager,
                                 @Qualifier("recommendationClock") Clock clock) {
        this.dailyRepository = dailyRepository;
        this.mealLogRepository = mealLogRepository;
        this.mappingRepository = mappingRepository;
        this.snapshotRepository = snapshotRepository;
        this.dishRepository = dishRepository;
        this.dishSearchService = dishSearchService;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.geminiClient = geminiClient;
        this.gemini = gemini;
        this.config = config;
        this.externalParser = externalParser;
        this.pipeline = pipeline;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    // =====================================================================
    // Profile
    // =====================================================================

    @Transactional
    public DailyNutritionSummaryResponse initializeDailyProfile(long userId, int age, String gender, double heightCm,
                                                               double weightKg, String activityLevel, String goal) {
        LocalDate today = today();
        UserDailyNutrition daily = dailyRepository.findByUserIdAndDate(userId, today).orElseGet(() ->
                dailyRepository.save(UserDailyNutrition.builder()
                        .userId(userId).date(today)
                        .age(Math.max(age, 1)).gender(normalizeGender(gender))
                        .heightCm(Math.max(heightCm, 1.0)).weightKg(Math.max(weightKg, 1.0))
                        .activityLevel(normalizeActivity(activityLevel)).goal(normalizeGoal(goal))
                        .active(true).build()));
        daily.setAge(Math.max(age, 1));
        daily.setGender(normalizeGender(gender));
        daily.setHeightCm(Math.max(heightCm, 1.0));
        daily.setWeightKg(Math.max(weightKg, 1.0));
        daily.setActivityLevel(normalizeActivity(activityLevel));
        daily.setGoal(normalizeGoal(goal));
        daily.setActive(true);
        computeDailyTargets(daily);
        dailyRepository.save(daily);

        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return toSummaryResponse(buildSummary(daily));
    }

    @Transactional
    public DailyNutritionProfileResponse updateDailyNutritionProfile(long userId, Integer age, String gender,
                                                                     Double heightCm, Double weightKg,
                                                                     String activityLevel, String goal) {
        UserDailyNutrition daily = dailyRepository.findByUserIdAndDate(userId, today())
                .orElseThrow(DailyNutritionProfileNotFoundException::new);
        if (age != null) {
            daily.setAge(Math.max(age, 1));
        }
        if (gender != null) {
            daily.setGender(normalizeGender(gender));
        }
        if (heightCm != null) {
            daily.setHeightCm(Math.max(heightCm, 1.0));
        }
        if (weightKg != null) {
            daily.setWeightKg(Math.max(weightKg, 1.0));
        }
        if (activityLevel != null) {
            daily.setActivityLevel(normalizeActivity(activityLevel));
        }
        if (goal != null) {
            daily.setGoal(normalizeGoal(goal));
        }
        computeDailyTargets(daily);
        dailyRepository.save(daily);
        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return buildProfile(daily);
    }

    @Transactional(readOnly = true)
    public DailyNutritionProfileResponse getDailyNutritionProfile(long userId) {
        UserDailyNutrition daily = dailyRepository.findByUserIdAndDate(userId, today())
                .orElseThrow(DailyNutritionProfileNotFoundException::new);
        return buildProfile(daily);
    }

    /**
     * profile-onboarding hook (Django {@code CustomerService.onboard_customer_profile}):
     * {@code UserDailyNutrition.get_or_create(user, today, defaults={height, weight})}, then set
     * height/weight, recompute targets, save — nothing else.
     */
    @Transactional
    public void applyOnboardingBodyMetrics(long userId, double heightCm, double weightKg) {
        LocalDate today = today();
        UserDailyNutrition daily = dailyRepository.findByUserIdAndDate(userId, today).orElseGet(() ->
                dailyRepository.save(UserDailyNutrition.builder().userId(userId).date(today)
                        .heightCm(Math.max(heightCm, 1.0)).weightKg(Math.max(weightKg, 1.0)).build()));
        daily.setHeightCm(Math.max(heightCm, 1.0));
        daily.setWeightKg(Math.max(weightKg, 1.0));
        computeDailyTargets(daily);
        dailyRepository.save(daily);
    }

    // =====================================================================
    // Meal parsing
    // =====================================================================

    @Transactional
    public DailyNutritionMealParseResponse parseOutsideMeals(long userId, String text, String mealTime) {
        UserDailyNutrition daily = getTodayDaily(userId);
        List<ParsedMeal> parsedMeals = parseMealText(text);
        Map<String, Map<String, Double>> nutritionCache = new HashMap<>();
        Map<String, Double> qualityCache = new HashMap<>();
        List<String> unresolved = new ArrayList<>();
        int created = 0;

        for (ParsedMeal meal : parsedMeals) {
            Dish dish = matchDish(meal.name());
            if (dish != null) {
                String dishUid = dish.getUid().toString();
                nutritionCache.computeIfAbsent(dishUid, k -> dishNutritionMapFor(List.of(k)).getOrDefault(k, emptyNutrition()));
                qualityCache.computeIfAbsent(dishUid, k -> dishQualityMapFor(List.of(k)).getOrDefault(k, 0.9));
                Map<String, Double> nutrition = nutritionCache.get(dishUid);
                double confidenceSource = computeSourceConfidence(meal.name(), dish.getName(), nutrition,
                        qualityCache.get(dishUid));
                createMealLog(daily, MealSource.PARSED, mealTime, meal.name(), dish, meal.quantityMultiplier(),
                        nutrition, meal.confidenceParse(), confidenceSource, Map.of("resolver", "dish_db"));
                created++;
                continue;
            }

            DishTranslationMapping mapping = matchTranslationMapping(meal.name());
            if (mapping != null) {
                Map<String, Double> nutrition = extractMappingNutrition(mapping);
                double confidenceSource = computeSourceConfidence(meal.name(), mapping.getVietnameseName(), nutrition,
                        mapping.getUsdaConfidence() == 0.0 ? 0.8 : mapping.getUsdaConfidence());
                createMealLog(daily, MealSource.USDA, mealTime, meal.name(), null, meal.quantityMultiplier(), nutrition,
                        meal.confidenceParse(), confidenceSource,
                        Map.of("resolver", "usda_mapping", "mapping_uid", mapping.getUid().toString()));
                created++;
                continue;
            }

            UsdaIngredient usda = matchUsdaIngredient(meal.name());
            if (usda != null) {
                Map<String, Double> nutrition = new LinkedHashMap<>();
                nutrition.put("protein_g", orZero(usda.protein()));
                nutrition.put("lipid_g", orZero(usda.lipid()));
                nutrition.put("carb_g", orZero(usda.carbohydrate()));
                nutrition.put("sodium_mg", orZero(usda.natri()));
                nutrition.put("fiber_g", orZero(usda.fiber()));
                // getattr(usda_ing, "usda_confidence", 0.9): Ingredient has no such field -> 0.9 always.
                double confidenceSource = computeSourceConfidence(meal.name(), usda.name(), nutrition, 0.9);
                createMealLog(daily, MealSource.USDA, mealTime, meal.name(), null, meal.quantityMultiplier(), nutrition,
                        meal.confidenceParse(), confidenceSource,
                        Map.of("resolver", "usda_ingredient", "ingredient_uid", usda.uid()));
                created++;
                continue;
            }

            Map<?, ?> generated = callGeminiRecipeGenerator(meal.name());
            if (generated != null && !generated.isEmpty()) {
                MappedRecipe recipe = normalizeAndMapRecipe(generated, name -> {
                    UsdaIngredient ing = matchUsdaIngredient(name);
                    return ing == null ? null : ing.uid();
                });
                if (!recipe.ingredients().isEmpty()) {
                    Map<String, Double> nutrition = computeRecipeNutrition(recipe.ingredients(),
                            loadIngredientNutrition(recipe.ingredients()));
                    double confidenceSource = Math.max(0.0, Math.min(1.0, recipe.confidence()));
                    try {
                        storeRecipeSnapshot(meal.name(), recipe.ingredients(), confidenceSource);
                    } catch (RuntimeException e) {
                        // Django: except Exception: pass
                    }
                    createMealLog(daily, MealSource.PARSED, mealTime, meal.name(), null, meal.quantityMultiplier(),
                            nutrition, meal.confidenceParse(), confidenceSource, Map.of("resolver", "gemini_generated"));
                    created++;
                    continue;
                }
            }
            unresolved.add(meal.name());
        }

        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return new DailyNutritionMealParseResponse(toSummaryResponse(buildSummary(daily)), created, unresolved);
    }

    /** Django {@code _parse_meal_text}: Gemini → external AI parser → heuristic. */
    List<ParsedMeal> parseMealText(String text) {
        List<ParsedMeal> parsed = callGeminiMealParser(text);
        if (!parsed.isEmpty()) {
            log.info("Parsed meal text with Gemini parser: {}", parsed);
            return parsed;
        }
        parsed = callExternalLlmParser(text);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        log.info("Falling back to heuristic meal parser for text: {}", text);
        return heuristicParse(text);
    }

    static String mealParserPrompt(String text) {
        return "\n"
                + "    You are a strict JSON generator.\n"
                + "\n"
                + "    Task:\n"
                + "    Extract Vietnamese meal text into JSON ONLY.\n"
                + "\n"
                + "    Rules:\n"
                + "    - Output ONLY valid JSON (no markdown, no explanation)\n"
                + "    - Schema:\n"
                + "    {\n"
                + "    \"meals\": [\n"
                + "        {\n"
                + "        \"name\": string,\n"
                + "        \"quantity_multiplier\": float,\n"
                + "        \"confidence_parse\": float\n"
                + "        }\n"
                + "    ]\n"
                + "    }\n"
                + "\n"
                + "    Text:\n"
                + "    " + text + "\n"
                + "    ";
    }

    static String recipePrompt(String dishName) {
        return "\nYou are a JSON-only generator. Given a Vietnamese dish name, output a JSON object with keys: "
                + "dish (string), ingredients (list of objects with name and weight_g as integer/float), "
                + "confidence (0-1).\n\nDish: " + dishName + "\n";
    }

    /** Django {@code _call_gemini_meal_parser}: [] without an API key; [] on ANY failure. No timeout (like Django). */
    List<ParsedMeal> callGeminiMealParser(String text) {
        String apiKey = gemini.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return List.of();
        }
        try {
            Duration timeout = gemini.getParserTimeoutSeconds() > 0 ? Duration.ofSeconds(gemini.getParserTimeoutSeconds()) : null;
            String content = geminiClient.generateContent(apiKey, gemini.getModel(), mealParserPrompt(text), timeout);
            Object parsed = parseJsonSafely(extractJson(content == null ? "" : content));
            if (!(parsed instanceof Map<?, ?> map)) {
                return List.of();
            }
            Object rows = map.containsKey("meals") ? map.get("meals") : List.of();
            return normalizeParsedRows(rows);
        } catch (RuntimeException e) {
            log.warn("GEMINI ERROR: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Django {@code _call_gemini_recipe_generator}.
     *
     * <p>🔴 PORT-NOTE — Django bug, fixed by default behind a flag: Django calls
     * {@code client.models.generate_content(model=..., contents=prompt, timeout=3.0)}, but the
     * installed google-genai (1.74.0) {@code generate_content} has no {@code timeout} keyword
     * (verified in backend/venv: {@code TypeError: Models.generate_content() got an unexpected
     * keyword argument 'timeout'}). The bare {@code except} turns that into {@code None}, so the
     * Gemini recipe tier of parse-meal NEVER works in Django — every meal not matched by dish /
     * mapping / ingredient lands in {@code unresolved_meals}. Default
     * ({@code app.gemini.preserve-recipe-timeout-bug=false}) = the intended behavior (call Gemini
     * with the 3 s timeout); {@code true} = Django's actual always-None outcome.
     */
    Map<?, ?> callGeminiRecipeGenerator(String dishName) {
        String apiKey = gemini.getApiKey();
        if (apiKey == null || apiKey.isEmpty() || dishName == null || dishName.isEmpty()) {
            return null;
        }
        if (gemini.isPreserveRecipeTimeoutBug()) {
            return null;
        }
        try {
            Duration timeout = Duration.ofMillis(Math.round(gemini.getRecipeTimeoutSeconds() * 1000));
            String content = geminiClient.generateContent(apiKey, gemini.getModel(), recipePrompt(dishName), timeout);
            Object parsed = parseJsonSafely(extractJson(content == null ? "" : content));
            return parsed instanceof Map<?, ?> map ? map : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Django {@code _call_external_llm_parser}: HTTP failure → []; payload handling outside the try (like Django). */
    List<ParsedMeal> callExternalLlmParser(String text) {
        if (!externalParser.isConfigured()) {
            return List.of();
        }
        Object payload;
        try {
            payload = externalParser.parseMeals(text);
        } catch (RuntimeException e) {
            return List.of();
        }
        Object rows = payload instanceof Map<?, ?> map ? (map.containsKey("meals") ? map.get("meals") : List.of()) : List.of();
        return normalizeParsedRows(rows);
    }

    /**
     * Django {@code _match_dish}: takes the #1 {@code DishSearchService.search(limit=1)} hit with
     * no floor — dish search's own {@code FUZZY_THRESHOLD} is dead code (see
     * {@link DishSearchService} PORT-NOTE), so in Django this ALWAYS returns the best fuzzy dish
     * whenever any live dish exists, however unrelated, making parse-meal tiers 2-4 unreachable in
     * practice. 🔴 User-decided fix (CLAUDE.md §0.6): by default, gate the hit on its own
     * {@code search_score} — the weighted fuzzy/exact + rating + popularity score
     * {@code DishSearchService} already computes and returns, not a new metric — being
     * &gt;= {@link RecommendationProperties#getDishMatchThreshold()} (NUTRITION_API.md: 0.6);
     * below it, fall through to the next tier exactly like "no dish found".
     * {@link RecommendationProperties#isPreserveDishMatchNoThreshold()} = true restores Django's
     * always-take-the-top-hit behavior. Does not change {@link DishSearchService#search} itself.
     */
    Dish matchDish(String mealName) {
        if (mealName == null || mealName.strip().isEmpty()) {
            return null;
        }
        try {
            DishSearchResponse result = dishSearchService.search(mealName, null, null, null, null, false, 1);
            if (result.results() == null || result.results().isEmpty()) {
                return null;
            }
            DishSearchResultResponse top = result.results().get(0);
            if (!config.isPreserveDishMatchNoThreshold() && top.searchScore() < config.getDishMatchThreshold()) {
                return null;
            }
            String uid = top.uid();
            if (uid == null || uid.isEmpty()) {
                return null;
            }
            return dishRepository.findActive(UUID.fromString(uid)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    DishTranslationMapping matchTranslationMapping(String mealName) {
        String normalized = RemoveAccents.apply((mealName == null ? "" : mealName).strip().toLowerCase(Locale.ROOT));
        if (normalized.isEmpty()) {
            return null;
        }
        return mappingRepository.findFirstByActiveTrueAndNormalizedVietnameseNameOrderByUidAsc(normalized)
                .or(() -> mappingRepository.findFirstActiveContaining(RecommendationService.escapeLike(normalized)))
                .orElse(null);
    }

    static Map<String, Double> extractMappingNutrition(DishTranslationMapping mapping) {
        Map<String, Object> payload = mapping.getNutritionPerServing() == null ? Map.of() : mapping.getNutritionPerServing();
        Map<String, Double> out = new LinkedHashMap<>();
        for (String k : MACROS) {
            Object v = payload.get(k);
            out.put(k, truthy(v) ? pyFloat(v) : 0.0);
        }
        return out;
    }

    /** The Ingredient columns {@code _match_usda_ingredient} callers read. */
    record UsdaIngredient(String uid, String name, Double protein, Double lipid, Double carbohydrate, Double natri,
                          Double fiber) {
    }

    private static double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    /**
     * Django {@code _match_usda_ingredient}: icontains on name_no_accent AND source icontains "USDA",
     * else icontains alone; {@code .first()} follows Ingredient's Meta ordering (name).
     */
    UsdaIngredient matchUsdaIngredient(String mealName) {
        if (mealName == null || mealName.isEmpty()) {
            return null;
        }
        String normalized = RemoveAccents.apply(mealName.strip().toLowerCase(Locale.ROOT));
        if (normalized.isEmpty()) {
            return null;
        }
        String like = "%" + RecommendationService.escapeLike(normalized) + "%";
        String base = "SELECT uid::text AS uid, name, protein, lipid, carbohydrate, natri, fiber FROM ingredient "
                + "WHERE deleted = FALSE AND UPPER(name_no_accent) LIKE UPPER(?) ";
        List<UsdaIngredient> rows = jdbc.query(base + "AND UPPER(source) LIKE UPPER('%USDA%') ORDER BY name LIMIT 1",
                (rs, i) -> usdaRow(rs), like);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        rows = jdbc.query(base + "ORDER BY name LIMIT 1", (rs, i) -> usdaRow(rs), like);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static UsdaIngredient usdaRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UsdaIngredient(rs.getString("uid"), rs.getString("name"),
                JdbcScoringDataSource.nullableDouble(rs, "protein"), JdbcScoringDataSource.nullableDouble(rs, "lipid"),
                JdbcScoringDataSource.nullableDouble(rs, "carbohydrate"), JdbcScoringDataSource.nullableDouble(rs, "natri"),
                JdbcScoringDataSource.nullableDouble(rs, "fiber"));
    }

    private Map<String, IngredientNutrition> loadIngredientNutrition(List<MappedIngredient> mapped) {
        List<String> ids = mapped.stream().map(MappedIngredient::ingredientUid).filter(Objects::nonNull).toList();
        Map<String, IngredientNutrition> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        jdbc.query("""
                SELECT uid::text AS uid, protein, lipid, carbohydrate, natri, fiber FROM ingredient
                WHERE uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                """, rs -> {
            map.put(rs.getString("uid"), new IngredientNutrition(JdbcScoringDataSource.nullableDouble(rs, "protein"),
                    JdbcScoringDataSource.nullableDouble(rs, "lipid"), JdbcScoringDataSource.nullableDouble(rs, "carbohydrate"),
                    JdbcScoringDataSource.nullableDouble(rs, "natri"), JdbcScoringDataSource.nullableDouble(rs, "fiber")));
        }, (Object) ids.toArray(new String[0]));
        return map;
    }

    private void storeRecipeSnapshot(String dishName, List<MappedIngredient> mapped, double confidence) {
        snapshotRepository.save(DishRecipeSnapshot.builder()
                .dishName(dishName)
                .normalizedName(RemoveAccents.apply((dishName == null ? "" : dishName).strip().toLowerCase(Locale.ROOT)))
                .ingredients(new ArrayList<>(mapped.stream().map(MappedIngredient::asMap).toList()))
                .source("GEMINI")
                .confidenceScore(confidence)
                .build());
    }

    private void createMealLog(UserDailyNutrition daily, MealSource source, String mealTime, String mealName, Dish dish,
                               double quantityMultiplier, Map<String, Double> nutrition, double confidenceParse,
                               double confidenceSource, Map<String, Object> rawPayload) {
        double multiplier = Math.max(quantityMultiplier == 0.0 ? 1.0 : quantityMultiplier, 0.1);
        String mt = (mealTime == null || mealTime.isEmpty() ? "UNKNOWN" : mealTime).toUpperCase(Locale.ROOT);
        mealLogRepository.save(DailyMealLog.builder()
                .dailyNutrition(daily)
                .source(source)
                .mealTime(MealTime.valueOf(mt))
                .mealName(mealName)
                .dish(dish)
                .quantityMultiplier(multiplier)
                .nutritionProteinG(nutrition.getOrDefault("protein_g", 0.0) * multiplier)
                .nutritionLipidG(nutrition.getOrDefault("lipid_g", 0.0) * multiplier)
                .nutritionCarbG(nutrition.getOrDefault("carb_g", 0.0) * multiplier)
                .nutritionSodiumMg(nutrition.getOrDefault("sodium_mg", 0.0) * multiplier)
                .nutritionFiberG(nutrition.getOrDefault("fiber_g", 0.0) * multiplier)
                .confidenceParse(clamp(confidenceParse))
                .confidenceSource(clamp(confidenceSource))
                .rawPayload(new LinkedHashMap<>(rawPayload))
                .build());
    }

    // =====================================================================
    // Balanced recommendations
    // =====================================================================

    /**
     * Django {@code get_balanced_recommendations}. The daily sync/recalc runs in its own
     * transaction; the pipeline call (autocommit, fail-soft raw SQL) stays outside any transaction.
     */
    public DailyNutritionRecommendationResponse getBalancedRecommendations(long userId, int limit) {
        record Prep(UserDailyNutrition daily, Summary summary) {
        }
        Prep prep = tx.execute(s -> {
            UserDailyNutrition daily = getTodayDaily(userId);
            syncAppOrderLogs(daily, null);
            recalculateConsumedTotals(daily);
            return new Prep(daily, buildSummary(daily));
        });
        Summary summary = prep.summary();
        DailyNutritionSummaryResponse summaryResponse = toSummaryResponse(summary);

        int poolSize = Math.max(60, limit * 5);
        RecommendationFeedResponse baseFeed = pipeline.recommendForUser(userId, poolSize, 0, true);
        List<RecommendationDishItemResponse> baseItems = baseFeed.items();

        if (baseItems.isEmpty()) {
            // Django: Dish.objects.filter(deleted=False, status="AVAILABLE").order_by("-avg_rating", "-final_score")[:limit * 2]
            int n = limit * 2;
            if (n < 0) {
                throw new IllegalArgumentException("Negative indexing is not supported.");
            }
            List<Dish> fallback = n == 0 ? List.of() : dishRepository.findByStatusRanked(DishStatus.AVAILABLE, PageRequest.of(0, n));
            if (fallback.isEmpty()) {
                return new DailyNutritionRecommendationResponse(summaryResponse, List.of());
            }
            NutritionValuesResponse zero = new NutritionValuesResponse(0, 0, 0, 0, 0);
            List<DailyNutritionRecommendationItemResponse> items = new ArrayList<>();
            for (Dish dish : fallback) {
                items.add(new DailyNutritionRecommendationItemResponse(dish.getUid().toString(), dish.getName(),
                        dish.getAttachment() != null ? dish.getAttachment().getPublicUrl() : null,
                        dish.getPrice().doubleValue(), dish.getAvgRating(), 0.0, 0.0, 0.5, 1.0, zero,
                        List.of("No personalized recommendations available, showing popular dishes")));
            }
            return new DailyNutritionRecommendationResponse(summaryResponse,
                    items.subList(0, Math.min(Math.max(limit, 1), items.size())));
        }

        Set<String> alreadyLogged = new HashSet<>(jdbc.queryForList("""
                SELECT dish_uid::text FROM daily_meal_log
                WHERE daily_nutrition_uid = ? AND is_deleted = FALSE AND dish_uid IS NOT NULL
                """, String.class, prep.daily().getUid()));

        List<FeedItem> feed = baseItems.stream().map(i -> new FeedItem(i.dishUid(), i.dishName(), i.publicUrl(),
                i.price(), i.avgRating(), i.score(), i.reasons())).toList();
        List<BalancedItem> ranked = rankBalanced(feed, alreadyLogged, limit, summary,
                this::dishNutritionMapFor, this::dishQualityMapFor);
        List<DailyNutritionRecommendationItemResponse> items = ranked.stream().map(r ->
                new DailyNutritionRecommendationItemResponse(r.dishUid(), r.dishName(), r.publicUrl(), r.price(),
                        r.avgRating(), r.baseRecommendationScore(), r.macroMatchScore(), r.finalScore(),
                        r.suggestedServings(), NutritionValuesResponse.of(r.nutritionImpact()), r.reasons())).toList();
        return new DailyNutritionRecommendationResponse(summaryResponse, items);
    }

    // =====================================================================
    // Summary / meal logs
    // =====================================================================

    @Transactional
    public DailyNutritionSummaryResponse getDailySummary(long userId) {
        UserDailyNutrition daily = getTodayDaily(userId);
        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return toSummaryResponse(buildSummary(daily));
    }

    @Transactional
    public DailyMealLogListResponse getDailyMealLogs(long userId) {
        UserDailyNutrition daily = getTodayDaily(userId);
        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return listResponse(daily);
    }

    private static UUID uuidOrThrow(String value) {
        // Django: filtering a UUIDField with a malformed string raises ValidationError -> 500.
        return UUID.fromString(value);
    }

    @Transactional
    public DailyMealLogListResponse updateDailyMealLog(long userId, String logUid, String mealName, String mealTime,
                                                       Double quantityMultiplier, String dishUid) {
        DailyMealLog log = mealLogRepository.findActiveForUser(uuidOrThrow(logUid), userId)
                .orElseThrow(() -> new IllegalStateException("Daily meal log not found"));
        if (mealName != null) {
            log.setMealName(mealName);
        }
        if (mealTime != null) {
            log.setMealTime(MealTime.valueOf((mealTime.isEmpty() ? "UNKNOWN" : mealTime).toUpperCase(Locale.ROOT)));
        }
        double currentQuantity = Math.max(log.getQuantityMultiplier() == 0.0 ? 1.0 : log.getQuantityMultiplier(), 0.1);
        double requested = (quantityMultiplier == null || quantityMultiplier == 0.0) ? currentQuantity : quantityMultiplier;
        double updatedQuantity = Math.max(requested, 0.1);

        if (dishUid != null) {
            Dish dish = dishRepository.findActive(uuidOrThrow(dishUid))
                    .orElseThrow(() -> new IllegalStateException("Dish not found"));
            String key = dish.getUid().toString();
            Map<String, Double> n = dishNutritionMapFor(List.of(key)).getOrDefault(key, emptyNutrition());
            log.setDish(dish);
            log.setNutritionProteinG(n.get("protein_g") * updatedQuantity);
            log.setNutritionLipidG(n.get("lipid_g") * updatedQuantity);
            log.setNutritionCarbG(n.get("carb_g") * updatedQuantity);
            log.setNutritionSodiumMg(n.get("sodium_mg") * updatedQuantity);
            log.setNutritionFiberG(n.get("fiber_g") * updatedQuantity);
        } else if (quantityMultiplier != null) {
            double scale = updatedQuantity / currentQuantity;
            log.setNutritionProteinG(log.getNutritionProteinG() * scale);
            log.setNutritionLipidG(log.getNutritionLipidG() * scale);
            log.setNutritionCarbG(log.getNutritionCarbG() * scale);
            log.setNutritionSodiumMg(log.getNutritionSodiumMg() * scale);
            log.setNutritionFiberG(log.getNutritionFiberG() * scale);
        }
        log.setQuantityMultiplier(updatedQuantity);
        mealLogRepository.save(log);

        UserDailyNutrition daily = log.getDailyNutrition();
        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return listResponse(daily);
    }

    @Transactional
    public DailyMealLogListResponse deleteDailyMealLog(long userId, String logUid) {
        DailyMealLog log = mealLogRepository.findActiveForUser(uuidOrThrow(logUid), userId)
                .orElseThrow(() -> new IllegalStateException("Daily meal log not found"));
        log.setDeleted(true);
        mealLogRepository.save(log);
        UserDailyNutrition daily = log.getDailyNutrition();
        syncAppOrderLogs(daily, null);
        recalculateConsumedTotals(daily);
        return listResponse(daily);
    }

    /** Django {@code sync_order_meal_logs} (called on commit when an order completes). Returns created count. */
    @Transactional
    public int syncOrderMealLogs(String orderUid) {
        List<OrderRow> rows = loadOrderRows("oi.order_uid = CAST(? AS uuid)", orderUid);
        if (rows.isEmpty()) {
            return 0;
        }
        OrderRow first = rows.get(0);
        if (first.ownerId() == null) {
            throw new IllegalStateException("order has no owner");
        }
        LocalDate date = first.orderCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate();
        UserDailyNutrition daily = getDailyForDate(first.ownerId(), date);
        int created = syncAppOrderLogs(daily, rows);
        recalculateConsumedTotals(daily);
        return created;
    }

    UserDailyNutrition getDailyForDate(long userId, LocalDate date) {
        return dailyRepository.findByUserIdAndDate(userId, date).orElseGet(() -> {
            UserDailyNutrition daily = UserDailyNutrition.builder().userId(userId).date(date).active(true).build();
            computeDailyTargets(daily);
            return dailyRepository.save(daily);
        });
    }

    UserDailyNutrition getTodayDaily(long userId) {
        return getDailyForDate(userId, today());
    }

    /** A completed order item as {@code _sync_app_order_logs} reads it. */
    record OrderRow(long orderItemId, String orderUid, String dishUid, String dishName, Integer quantity,
                    Long ownerId, java.time.Instant orderCreatedAt, LocalTime deliveryTime) {
    }

    private List<OrderRow> loadOrderRows(String where, Object... args) {
        return jdbc.query("""
                SELECT oi.id, oi.order_uid::text AS order_uid, oi.dish_uid::text AS dish_uid, oi.dish_name, oi.quantity,
                       o.owner_id, o.created_at, o.checkout_uid
                FROM order_item oi
                JOIN "order" o ON o.uid = oi.order_uid
                WHERE o.status = 'COMPLETED' AND oi.dish_uid IS NOT NULL AND """ + " " + where + " ORDER BY oi.id",
                (rs, i) -> {
                    int q = rs.getInt("quantity");
                    Integer quantity = rs.wasNull() ? null : q;
                    long owner = rs.getLong("owner_id");
                    Long ownerId = rs.wasNull() ? null : owner;
                    String checkoutUid = rs.getString("checkout_uid");
                    return new OrderRow(rs.getLong("id"), rs.getString("order_uid"), rs.getString("dish_uid"),
                            rs.getString("dish_name"), quantity, ownerId,
                            JdbcScoringDataSource.instant(rs, "created_at"), deliveryTimeOf(checkoutUid));
                }, args);
    }

    /**
     * {@code row.order.checkout.delivery_time}, read through {@code order}'s Checkout entity: that
     * entity's LocalTime goes through Hibernate's {@code jdbc.time_zone: UTC} conversion on write,
     * so the raw column value is JVM-zone-shifted and must be read back the same way.
     */
    private LocalTime deliveryTimeOf(String checkoutUid) {
        if (checkoutUid == null) {
            return null;
        }
        com.amomeal.marketplace.order.entity.Checkout checkout =
                entityManager.find(com.amomeal.marketplace.order.entity.Checkout.class, UUID.fromString(checkoutUid));
        return checkout == null ? null : checkout.getDeliveryTime();
    }

    /**
     * Django {@code _sync_app_order_logs}: one APP meal log per completed order item of that day,
     * idempotent through three lookups (source_ref; raw_payload.order_item_id; raw_payload.order_uid
     * + dish + quantity). The lookups do NOT filter is_deleted, so a log the user deleted is never
     * re-created (faithful).
     */
    int syncAppOrderLogs(UserDailyNutrition daily, List<OrderRow> orderRows) {
        List<OrderRow> rows = (orderRows != null && !orderRows.isEmpty()) ? orderRows
                : loadOrderRows("o.owner_id = ? AND (o.created_at AT TIME ZONE 'UTC')::date = ?",
                daily.getUserId(), daily.getDate());
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> dishIds = rows.stream().map(OrderRow::dishUid).filter(Objects::nonNull).toList();
        Map<String, Map<String, Double>> nutritionMap = dishNutritionMapFor(dishIds);
        Map<String, Double> qualityMap = dishQualityMapFor(dishIds);

        int created = 0;
        for (OrderRow row : rows) {
            if (row.dishUid() == null) {
                continue;
            }
            String sourceRef = "order_item:" + row.orderItemId();
            UUID dishUuid = UUID.fromString(row.dishUid());
            double quantity = Math.max(row.quantity() == null || row.quantity() == 0 ? 1.0 : row.quantity(), 1.0);
            Optional<DailyMealLog> existing = mealLogRepository.findFirstByDailyNutritionAndSourceAndSourceRefOrderByUidAsc(
                    daily, MealSource.APP, sourceRef);
            if (existing.isEmpty()) {
                mealLogRepository.flush();
                existing = mealLogRepository.findAppLogByOrderItemId(daily.getUid(), String.valueOf(row.orderItemId()));
            }
            if (existing.isEmpty()) {
                existing = mealLogRepository.findAppLogByOrderUidDishQuantity(daily.getUid(), row.orderUid(), dishUuid, quantity);
            }
            if (existing.isPresent()) {
                DailyMealLog log = existing.get();
                if (log.getSourceRef() == null || log.getSourceRef().isEmpty()) {
                    log.setSourceRef(sourceRef);
                    Map<String, Object> payload = new LinkedHashMap<>(log.getRawPayload() == null ? Map.of() : log.getRawPayload());
                    payload.put("order_uid", row.orderUid());
                    payload.put("order_item_id", String.valueOf(row.orderItemId()));
                    log.setRawPayload(payload);
                    mealLogRepository.save(log);
                }
                continue;
            }
            Map<String, Double> nutrition = nutritionMap.getOrDefault(row.dishUid(), emptyNutrition());
            Dish dishRef = entityManager.getReference(Dish.class, dishUuid);
            String mealName = (row.dishName() != null && !row.dishName().isEmpty()) ? row.dishName()
                    : dishRepository.findById(dishUuid).map(Dish::getName).orElse("Unknown dish");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("order_uid", row.orderUid());
            payload.put("order_item_id", String.valueOf(row.orderItemId()));
            mealLogRepository.save(DailyMealLog.builder()
                    .dailyNutrition(daily)
                    .source(MealSource.APP)
                    .mealTime(inferMealTime(row.deliveryTime()))
                    .dish(dishRef)
                    .mealName(mealName)
                    .quantityMultiplier(quantity)
                    .nutritionProteinG(nutrition.get("protein_g") * quantity)
                    .nutritionLipidG(nutrition.get("lipid_g") * quantity)
                    .nutritionCarbG(nutrition.get("carb_g") * quantity)
                    .nutritionSodiumMg(nutrition.get("sodium_mg") * quantity)
                    .nutritionFiberG(nutrition.get("fiber_g") * quantity)
                    .confidenceParse(1.0)
                    .confidenceSource(qualityMap.getOrDefault(row.dishUid(), 0.98))
                    .rawPayload(payload)
                    .sourceRef(sourceRef)
                    .build());
            created++;
        }
        return created;
    }

    void recalculateConsumedTotals(UserDailyNutrition daily) {
        double p = 0, l = 0, c = 0, s = 0, f = 0;
        for (DailyMealLog log : mealLogRepository.findAllByDailyNutritionAndDeletedFalse(daily)) {
            p += log.getNutritionProteinG();
            l += log.getNutritionLipidG();
            c += log.getNutritionCarbG();
            s += log.getNutritionSodiumMg();
            f += log.getNutritionFiberG();
        }
        daily.setConsumedProteinG(PyMath.round(p, 3));
        daily.setConsumedLipidG(PyMath.round(l, 3));
        daily.setConsumedCarbG(PyMath.round(c, 3));
        daily.setConsumedSodiumMg(PyMath.round(s, 3));
        daily.setConsumedFiberG(PyMath.round(f, 3));
        dailyRepository.save(daily);
    }

    Summary buildSummary(UserDailyNutrition daily) {
        List<LogValues> logs = mealLogRepository.findAllByDailyNutritionAndDeletedFalse(daily).stream()
                .map(l -> new LogValues(l.getNutritionProteinG(), l.getNutritionLipidG(), l.getNutritionCarbG(),
                        l.getNutritionSodiumMg(), l.getNutritionFiberG(), l.getConfidenceParse(), l.getConfidenceSource()))
                .toList();
        return DailyNutritionMath.buildSummary(daily, consumedUncertainty(logs));
    }

    static DailyNutritionSummaryResponse toSummaryResponse(Summary s) {
        return new DailyNutritionSummaryResponse(s.date(), s.bmrKcal(), s.tdeeKcal(),
                NutritionValuesResponse.of(s.target()), NutritionValuesResponse.of(s.consumed()),
                NutritionValuesResponse.of(s.remaining()));
    }

    private DailyNutritionProfileResponse buildProfile(UserDailyNutrition daily) {
        DailyNutritionSummaryResponse s = toSummaryResponse(buildSummary(daily));
        return new DailyNutritionProfileResponse(daily.getAge(), daily.getGender().name(), daily.getHeightCm(),
                daily.getWeightKg(), daily.getActivityLevel().name(), daily.getGoal().name(), s.date(), s.bmrKcal(),
                s.tdeeKcal(), s.target(), s.consumed(), s.remaining());
    }

    private DailyMealLogListResponse listResponse(UserDailyNutrition daily) {
        DailyNutritionSummaryResponse summary = toSummaryResponse(buildSummary(daily));
        mealLogRepository.flush();
        List<DailyMealLogItemResponse> items = mealLogRepository.findActiveOrdered(daily).stream()
                .map(DailyNutritionService::serializeMealLog).toList();
        return new DailyMealLogListResponse(summary, items);
    }

    static DailyMealLogItemResponse serializeMealLog(DailyMealLog log) {
        Dish dish = log.getDish();
        String imageUrl = null;
        Double price = null;
        if (dish != null) {
            if (dish.getAttachment() != null) {
                imageUrl = dish.getAttachment().getPublicUrl();
            }
            price = dish.getPrice() != null ? dish.getPrice().doubleValue() : null;
        }
        return new DailyMealLogItemResponse(
                log.getUid().toString(),
                log.getSource().name(),
                log.getMealTime() == null ? "UNKNOWN" : log.getMealTime().name(),
                dish != null ? dish.getUid().toString() : null,
                dish != null ? dish.getName() : null,
                log.getMealName() == null ? "" : log.getMealName(),
                log.getQuantityMultiplier(),
                log.getNutritionProteinG(), log.getNutritionLipidG(), log.getNutritionCarbG(),
                log.getNutritionSodiumMg(), log.getNutritionFiberG(),
                log.getConfidenceParse(), log.getConfidenceSource(),
                log.getRawPayload() == null ? Map.of() : log.getRawPayload(),
                imageUrl, price,
                PyMath.isoformat(log.getCreatedAt()), PyMath.isoformat(log.getUpdatedAt()));
    }

    // =====================================================================
    // Dish nutrition / quality maps (DB half)
    // =====================================================================

    private List<DishIngredientRow> dishIngredientRows(List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT di.dish_uid::text AS dish_id, di.protein, di.lipid, di.carbohydrate, di.natri, di.fiber,
                       d.serving_size, di.weight, di.confidence
                FROM dish_ingredient di JOIN dish d ON d.uid = di.dish_uid
                WHERE di.dish_uid = ANY(CAST(? AS uuid[])) AND di.deleted = FALSE AND d.deleted = FALSE
                ORDER BY di.dish_uid
                """, (rs, i) -> {
            int ss = rs.getInt("serving_size");
            Integer serving = rs.wasNull() ? null : ss;
            return new DishIngredientRow(rs.getString("dish_id"), JdbcScoringDataSource.nullableDouble(rs, "protein"),
                    JdbcScoringDataSource.nullableDouble(rs, "lipid"), JdbcScoringDataSource.nullableDouble(rs, "carbohydrate"),
                    JdbcScoringDataSource.nullableDouble(rs, "natri"), JdbcScoringDataSource.nullableDouble(rs, "fiber"),
                    serving, JdbcScoringDataSource.nullableDouble(rs, "weight"),
                    JdbcScoringDataSource.nullableDouble(rs, "confidence"));
        }, (Object) dishIds.toArray(new String[0]));
    }

    Map<String, Map<String, Double>> dishNutritionMapFor(List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        return dishNutritionMap(dishIngredientRows(dishIds));
    }

    Map<String, Double> dishQualityMapFor(List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        return dishQualityMap(dishIngredientRows(dishIds), dishIds);
    }
}
