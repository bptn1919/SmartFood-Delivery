package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.recommendation.config.RecommendationProperties;
import com.amomeal.marketplace.recommendation.dto.RecommendationDishItemResponse;
import com.amomeal.marketplace.recommendation.dto.RecommendationFeedMetaResponse;
import com.amomeal.marketplace.recommendation.dto.RecommendationFeedResponse;
import com.amomeal.marketplace.recommendation.entity.UserFoodPreferenceFeature;
import com.amomeal.marketplace.recommendation.repository.RecommendationDishRepository;
import com.amomeal.marketplace.recommendation.repository.UserFoodPreferenceFeatureRepository;
import com.amomeal.marketplace.users.exception.UserNotFoundException;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 1:1 port of ../../backend/recommendation/services/pipeline.py::RecommendationPipelineService —
 * the {@code GET /me/dishes} feed: feature → issue profile → ensure dish vectors → user vector →
 * candidates → scoring → MMR → page → reasons.
 *
 * <p>Deliberately NOT {@code @Transactional}: Django runs this in autocommit, and several raw-SQL
 * steps are fail-soft ({@code try/except}); inside one Postgres transaction a swallowed error would
 * abort every later statement. JdbcTemplate/repository calls therefore each autocommit, exactly
 * like Django.
 */
@Service
public class RecommendationPipelineService {

    /** Django {@code ISSUE_KEYS} (same 26 labels, same order as DEFAULT_ISSUES). */
    public static final List<String> ISSUE_KEYS = List.of(
            "mặn", "nhạt", "cay", "ngọt", "chua", "đắng", "dai", "khô", "nhão", "dầu mỡ", "tanh", "hôi",
            "hư thiu", "sạn", "giao chậm", "giao sai", "giao thiếu", "đóng gói kém", "nguội", "ít topping",
            "ít sốt", "ít nhân", "làm lâu", "giá đắt", "phục vụ kém", "chưa tốt");

    private final RecommendationProperties config;
    private final VectorIndexService vectorIndexService;
    private final JdbcTemplate jdbc;
    private final RecommendationDishRepository dishRepository;
    private final UserFoodPreferenceFeatureRepository featureRepository;
    private final CustomUserRepository userRepository;
    private final JdbcScoringDataSource scoringDataSource;
    private final RecommendationService recommendationService;
    private final Clock clock;

    public RecommendationPipelineService(RecommendationProperties config, VectorIndexService vectorIndexService,
                                         JdbcTemplate jdbc, RecommendationDishRepository dishRepository,
                                         UserFoodPreferenceFeatureRepository featureRepository,
                                         CustomUserRepository userRepository, JdbcScoringDataSource scoringDataSource,
                                         @Lazy RecommendationService recommendationService,
                                         @Qualifier("recommendationClock") Clock clock) {
        this.config = config;
        this.vectorIndexService = vectorIndexService;
        this.jdbc = jdbc;
        this.dishRepository = dishRepository;
        this.featureRepository = featureRepository;
        this.userRepository = userRepository;
        this.scoringDataSource = scoringDataSource;
        this.recommendationService = recommendationService;
        this.clock = clock;
    }

    /** The user feature as Django's {@code _get_user_feature} dict. */
    public record UserFeature(List<String> favoriteDishIds, List<String> favoriteIngredientIds,
                              List<String> allergicIngredientIds, String allergyMode, String dietMode,
                              String dietLevel) {
    }

    static List<String> normalizeJsonIds(List<Object> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).strip();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    UserFeature getUserFeature(long userId) {
        UserFoodPreferenceFeature feature = featureRepository.findByUserId(userId).orElse(null);
        if (feature == null) {
            return new UserFeature(List.of(), List.of(), List.of(), "WARN", "NONE", "NONE");
        }
        return new UserFeature(
                normalizeJsonIds(feature.getFavoriteDishIds()),
                normalizeJsonIds(feature.getFavoriteIngredientIds()),
                normalizeJsonIds(feature.getAllergicIngredientIds()),
                feature.getAllergyMode() == null ? "WARN" : feature.getAllergyMode().name(),
                feature.getDietMode() == null ? "NONE" : feature.getDietMode().name(),
                feature.getDietLevel() == null ? "NONE" : feature.getDietLevel().name());
    }

    private Map<String, Boolean> allergyWarningMap(List<String> dishIds, List<String> allergicIngredientIds) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        if (dishIds.isEmpty() || allergicIngredientIds.isEmpty()) {
            dishIds.forEach(id -> map.put(id, false));
            return map;
        }
        Set<String> flagged = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT dish_uid::text FROM dish_ingredient
                WHERE dish_uid = ANY(CAST(? AS uuid[])) AND ingredient_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                """, String.class, dishIds.toArray(new String[0]), allergicIngredientIds.toArray(new String[0])));
        dishIds.forEach(id -> map.put(id, flagged.contains(id)));
        return map;
    }

    public ScoringEngine newScoringEngine() {
        return new ScoringEngine(
                config.getHistoryDecayLambda(),
                config.getRecencyDecayLambda(),
                config.getWeights().asMap(),
                config.getPenaltyGamma(),
                config.getPenaltyCorrelationThreshold(),
                config.getDietBalancedTarget(),
                config.getFavoriteRecencyDecayLambda(),
                config.getUserVectorEmaAlpha(),
                config.isPrioritizeUnorderedDishes(),
                clock);
    }

    public CandidateGenerator newCandidateGenerator(int poolSize) {
        return new CandidateGenerator(poolSize, jdbc, dishRepository, clock);
    }

    public RecommendationFeedResponse recommendForUser(long userId, int limit, int offset, boolean includeExplain) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }
        limit = Math.max(1, Math.min(limit, 100));
        offset = Math.max(0, offset);

        long start = System.nanoTime();

        UserFeature feature = getUserFeature(userId);
        RecommendationService.IssueProfile profile = recommendationService.getUserIssueSensitivityProfile(userId);
        Map<String, Double> issueProfile = new LinkedHashMap<>();
        for (String key : ISSUE_KEYS) {
            issueProfile.put(key, profile.issueProfile().getOrDefault(key, 0.0));
        }
        double issueConfidence = profile.confidence();
        int issuePoints = profile.dataPoints();

        vectorIndexService.ensureDishVectors();
        VectorIndexService.UserVector userVector = vectorIndexService.getOrBuildUserVector(userId,
                config.getHistoryDecayLambda());

        CandidateGenerator generator = newCandidateGenerator(config.getCandidatePoolSize());
        List<Dish> candidates = generator.generate(
                feature.favoriteDishIds(), feature.favoriteIngredientIds(), feature.allergicIngredientIds(),
                feature.allergyMode(), userVector.vector(), config.isUsePgvectorAnnCandidates(),
                config.getCandidateMaxPerCategory());

        List<ScoredItem> scored = newScoringEngine().score(scoringDataSource, userId, candidates,
                feature.favoriteDishIds(), feature.favoriteIngredientIds(), feature.dietMode(), feature.dietLevel(),
                issueProfile, issueConfidence, userVector.vector());

        int rerankTake = Math.min(scored.size(), Math.max(limit + offset, limit));
        MmrReranker reranker = new MmrReranker(config.getMmrLambda(), config.isUsePgvectorSimilarity(),
                vectorIndexService::pgvectorCosineSimilarity);
        List<ScoredItem> reranked = reranker.rerank(scored, rerankTake);

        List<ScoredItem> paged = reranked.subList(Math.min(offset, reranked.size()),
                Math.min(offset + limit, reranked.size()));

        List<String> dishIds = paged.stream().map(i -> i.getDish().getUid().toString()).toList();
        Set<String> favoriteSet = new HashSet<>(feature.favoriteDishIds());
        String allergyMode = feature.allergyMode() == null || feature.allergyMode().isEmpty()
                ? "WARN" : feature.allergyMode().toUpperCase(Locale.ROOT);
        Map<String, Boolean> warningMap;
        if (allergyMode.equals("WARN")) {
            warningMap = allergyWarningMap(dishIds, feature.allergicIngredientIds());
        } else {
            warningMap = new LinkedHashMap<>();
            dishIds.forEach(id -> warningMap.put(id, false));
        }

        List<RecommendationDishItemResponse> data = new ArrayList<>();
        for (ScoredItem item : paged) {
            Dish dish = item.getDish();
            String publicUrl = null;
            if (dish.getAttachment() != null && dish.getAttachment().getPublicUrl() != null
                    && !dish.getAttachment().getPublicUrl().isEmpty()) {
                publicUrl = dish.getAttachment().getPublicUrl();
            }
            String uid = dish.getUid().toString();
            data.add(new RecommendationDishItemResponse(
                    uid, dish.getName(), publicUrl, dish.getPrice().doubleValue(), dish.getAvgRating(),
                    favoriteSet.contains(uid), warningMap.getOrDefault(uid, false),
                    item.getScore(), item.getBaseScore(), item.getFavoriteScore(), item.getHistoryScore(),
                    item.getIssuePenalty(), item.getPreferenceNutritionMismatchPenalty(),
                    item.getPreferenceNutritionMismatchPenalty(),
                    includeExplain ? Explain.buildReasons(item) : List.of()));
        }

        int elapsedMs = (int) ((System.nanoTime() - start) / 1_000_000);
        RecommendationFeedMetaResponse meta = new RecommendationFeedMetaResponse(
                limit, offset, candidates.size(), scored.size(), reranked.size(),
                PyMath.round(issueConfidence, 3), issuePoints, elapsedMs, config.getWeights().asMap(),
                config.getMmrLambda(), config.getPenaltyGamma(), config.isUsePgvectorAnnCandidates(),
                userVector.source());
        return new RecommendationFeedResponse(data, meta);
    }
}
