package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.recommendation.config.RecommendationProperties;
import com.amomeal.marketplace.recommendation.dto.BetterDishForIssueResponse;
import com.amomeal.marketplace.recommendation.dto.BetterDishItem;
import com.amomeal.marketplace.recommendation.dto.UserFoodPreferenceFeatureResponse;
import com.amomeal.marketplace.recommendation.dto.UserIssueSensitivityProfileResponse;
import com.amomeal.marketplace.recommendation.exception.PreferencesNotFoundException;
import com.amomeal.marketplace.recommendation.repository.RecommendationDishRepository;
import com.amomeal.marketplace.recommendation.repository.UserFoodPreferenceFeatureRepository;
import com.amomeal.marketplace.users.exception.UserNotFoundException;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1:1 port of ../../backend/recommendation/services/recommendation.py::RecommendationService —
 * the facade: user issue-sensitivity profile, preference features (+ {@code rebuild_user_feature}),
 * the feed (delegates to {@link RecommendationPipelineService}), the daily-nutrition endpoints
 * (validate user, then delegate to {@link DailyNutritionService}) and
 * {@code find_better_dish_for_issue}.
 *
 * <p>PORT-NOTE: {@code recommendation/services/__init__.py} in Django is a stale verbatim copy of
 * {@code review}'s ReviewService (dead code, never imported by name) — not ported.
 */
@Slf4j
@Service
public class RecommendationService {

    public static final double TIME_DECAY_LAMBDA = 0.03;
    public static final double MIN_WEIGHT_THRESHOLD = 0.1;

    public static final List<String> DEFAULT_ISSUES = RecommendationPipelineService.ISSUE_KEYS;
    public static final Set<String> ISSUE_WHITELIST = new LinkedHashSet<>(DEFAULT_ISSUES);

    private final CustomUserRepository userRepository;
    private final UserFoodPreferenceFeatureRepository featureRepository;
    private final RecommendationDishRepository dishRepository;
    private final JdbcTemplate jdbc;
    private final VectorIndexService vectorIndexService;
    private final RecommendationPipelineService pipeline;
    private final DailyNutritionService dailyNutritionService;
    private final UserFeatureWriter featureWriter;
    private final PostCommitRunner postCommit;
    private final RecommendationProperties config;
    private final Clock clock;

    public RecommendationService(CustomUserRepository userRepository, UserFoodPreferenceFeatureRepository featureRepository,
                                 RecommendationDishRepository dishRepository, JdbcTemplate jdbc,
                                 VectorIndexService vectorIndexService, @Lazy RecommendationPipelineService pipeline,
                                 @Lazy DailyNutritionService dailyNutritionService, UserFeatureWriter featureWriter,
                                 PostCommitRunner postCommit, RecommendationProperties config,
                                 @Qualifier("recommendationClock") Clock clock) {
        this.userRepository = userRepository;
        this.featureRepository = featureRepository;
        this.dishRepository = dishRepository;
        this.jdbc = jdbc;
        this.vectorIndexService = vectorIndexService;
        this.pipeline = pipeline;
        this.dailyNutritionService = dailyNutritionService;
        this.featureWriter = featureWriter;
        this.postCommit = postCommit;
        this.config = config;
        this.clock = clock;
    }

    // =====================================================================
    // Issue sensitivity profile
    // =====================================================================

    /** One {@code Review.values("issue", "weight", "created_at")} row of the user. */
    public record IssueRow(String issue, Double weight, Instant createdAt) {
    }

    public record IssueProfile(long userId, Map<String, Double> issueProfile, double confidence, int dataPoints) {
        public UserIssueSensitivityProfileResponse toResponse() {
            return new UserIssueSensitivityProfileResponse(userId, issueProfile, confidence, dataPoints);
        }
    }

    public IssueProfile getUserIssueSensitivityProfile(long userId) {
        validateUser(userId);
        // ReviewORM.get_user_issue_rows: owner, deleted=False, issue not null/"" (Meta ordering -created_at).
        List<IssueRow> rows = jdbc.query("""
                SELECT issue, weight, created_at FROM review
                WHERE owner_id = ? AND deleted = FALSE AND issue IS NOT NULL AND issue <> ''
                ORDER BY created_at DESC
                """, (rs, i) -> new IssueRow(rs.getString("issue"), JdbcScoringDataSource.nullableDouble(rs, "weight"),
                JdbcScoringDataSource.instant(rs, "created_at")), userId);
        return computeIssueProfile(userId, rows, clock.instant());
    }

    /** The pure part ({@code _preprocess} → {@code _aggregate} → {@code _compute_confidence}); pinned against Python. */
    public static IssueProfile computeIssueProfile(long userId, List<IssueRow> rows, Instant now) {
        if (rows.isEmpty()) {
            Map<String, Double> empty = new LinkedHashMap<>();
            DEFAULT_ISSUES.forEach(i -> empty.put(i, 0.0));
            return new IssueProfile(userId, empty, 0.0, 0);
        }
        // _preprocess
        record Processed(String issue, double value, double decay) {
        }
        List<Processed> processed = new ArrayList<>();
        for (IssueRow row : rows) {
            if (row.issue() == null || row.issue().isEmpty()) {
                continue;
            }
            String issue = classifyIssue(row.issue());
            if (issue == null || !ISSUE_WHITELIST.contains(issue)) {
                continue;
            }
            double weight = row.weight() != null ? row.weight() : 0.0;
            weight = Math.max(0.0, Math.min(weight, 1.0));
            if (weight < MIN_WEIGHT_THRESHOLD) {
                continue;
            }
            double ageDays = row.createdAt() != null ? PyMath.ageDays(now, row.createdAt()) : 0.0;
            double decay = Math.exp(-TIME_DECAY_LAMBDA * ageDays);
            processed.add(new Processed(issue, weight * decay, decay));
        }
        // _aggregate
        Map<String, Double> weightSum = new HashMap<>();
        Map<String, Double> decaySum = new HashMap<>();
        Map<String, List<Double>> values = new LinkedHashMap<>();
        double totalScore = 0.0;
        for (Processed p : processed) {
            weightSum.merge(p.issue(), p.value(), Double::sum);
            decaySum.merge(p.issue(), p.decay(), Double::sum);
            values.computeIfAbsent(p.issue(), k -> new ArrayList<>()).add(p.value());
            totalScore += p.value();
        }
        Map<String, Double> profile = new LinkedHashMap<>();
        for (String issue : DEFAULT_ISSUES) {
            double ds = decaySum.getOrDefault(issue, 0.0);
            double score = ds > 0 ? weightSum.get(issue) / ds : 0.0;
            profile.put(issue, PyMath.round(score, 3));
        }
        // _compute_confidence
        int dataPoints = processed.size();
        double confidence;
        if (dataPoints == 0) {
            confidence = 0.0;
        } else {
            double coverage = 1 - Math.exp(-(double) dataPoints / 25);
            double avgSignal = totalScore / dataPoints;
            double varianceSum = 0.0;
            int count = 0;
            for (List<Double> vs : values.values()) {
                if (vs.size() > 1) {
                    double sum = 0;
                    for (double v : vs) {
                        sum += v;
                    }
                    double mean = sum / vs.size();
                    double sq = 0;
                    for (double v : vs) {
                        sq += (v - mean) * (v - mean);
                    }
                    varianceSum += sq / vs.size();
                    count++;
                }
            }
            double avgVariance = count > 0 ? varianceSum / count : 0.0;
            double variancePenalty = Math.min(1.0, avgVariance);
            confidence = 0.5 * coverage + 0.3 * avgSignal + 0.2 * (1 - variancePenalty);
            confidence = Math.max(0.0, Math.min(1.0, confidence));
        }
        return new IssueProfile(userId, profile, PyMath.round(confidence, 3), dataPoints);
    }

    static String normalizeIssue(String issue) {
        String normalized = RemoveAccents.apply((issue == null ? "" : issue).strip().toLowerCase(Locale.ROOT));
        return normalized.replace(" ", "_").replace("-", "_");
    }

    /** Django {@code _classify_issue}: exact whitelist label, else accent/space/hyphen-insensitive match. */
    public static String classifyIssue(String rawIssue) {
        String normalized = (rawIssue == null ? "" : rawIssue).strip().toLowerCase(Locale.ROOT);
        String compact = normalizeIssue(normalized);
        if (ISSUE_WHITELIST.contains(normalized)) {
            return normalized;
        }
        for (String issue : ISSUE_WHITELIST) {
            if (normalizeIssue(issue).equals(compact)) {
                return issue;
            }
        }
        return null;
    }

    void validateUser(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }
    }

    // =====================================================================
    // Preference features
    // =====================================================================

    /**
     * Django {@code rebuild_user_feature(user_id, update_fields)} + the {@code post_save} signal on
     * UserFoodPreferenceFeature ({@code recommendation/signals.py}: {@code transaction.on_commit →
     * refresh_user_vector}). {@code updateFields == null} = rebuild everything.
     */
    public void rebuildUserFeature(long userId, List<String> updateFields) {
        featureWriter.rebuild(userId, updateFields);
        postCommit.afterCommitNonTransactional(() ->
                vectorIndexService.refreshUserVector(userId, config.getHistoryDecayLambda(), false));
    }

    public UserFoodPreferenceFeatureResponse getUserFoodPreferenceFeatures(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }
        return featureRepository.findByUserId(userId)
                .map(UserFoodPreferenceFeatureResponse::of)
                .orElseThrow(PreferencesNotFoundException::new);
    }

    // =====================================================================
    // Feed + daily nutrition facade
    // =====================================================================

    public com.amomeal.marketplace.recommendation.dto.RecommendationFeedResponse getRecommendedDishes(
            long userId, int limit, int offset, boolean includeExplain) {
        return pipeline.recommendForUser(userId, limit, offset, includeExplain);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyNutritionSummaryResponse initDailyNutrition(
            long userId, int age, String gender, double heightCm, double weightKg, String activityLevel, String goal) {
        validateUser(userId);
        return dailyNutritionService.initializeDailyProfile(userId, age, gender, heightCm, weightKg, activityLevel, goal);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyNutritionMealParseResponse parseDailyMeal(
            long userId, String text, String mealTime) {
        validateUser(userId);
        return dailyNutritionService.parseOutsideMeals(userId, text, mealTime);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyNutritionSummaryResponse getDailyNutritionSummary(long userId) {
        validateUser(userId);
        return dailyNutritionService.getDailySummary(userId);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyMealLogListResponse getDailyMealLogs(long userId) {
        validateUser(userId);
        return dailyNutritionService.getDailyMealLogs(userId);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyMealLogListResponse updateDailyMealLog(
            long userId, String logUid, String mealName, String mealTime, Double quantityMultiplier, String dishUid) {
        validateUser(userId);
        return dailyNutritionService.updateDailyMealLog(userId, logUid, mealName, mealTime, quantityMultiplier, dishUid);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyMealLogListResponse deleteDailyMealLog(long userId, String logUid) {
        validateUser(userId);
        return dailyNutritionService.deleteDailyMealLog(userId, logUid);
    }

    public int syncOrderMealLogs(String orderUid) {
        return dailyNutritionService.syncOrderMealLogs(orderUid);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyNutritionRecommendationResponse getDailyBalancedRecommendations(
            long userId, int limit) {
        validateUser(userId);
        return dailyNutritionService.getBalancedRecommendations(userId, limit);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyNutritionProfileResponse updateDailyNutritionProfile(
            long userId, Integer age, String gender, Double heightCm, Double weightKg, String activityLevel, String goal) {
        validateUser(userId);
        return dailyNutritionService.updateDailyNutritionProfile(userId, age, gender, heightCm, weightKg, activityLevel, goal);
    }

    public com.amomeal.marketplace.recommendation.dto.DailyNutritionProfileResponse getDailyNutritionProfile(long userId) {
        validateUser(userId);
        return dailyNutritionService.getDailyNutritionProfile(userId);
    }

    // =====================================================================
    // find_better_dish_for_issue
    // =====================================================================

    /** One {@code get_bulk_dish_rating_stats} entry. */
    public record RatingStats(double avgRating, int totalReviews) {
    }

    /** One scored candidate (Django dict {dish_uid, avg_rating, total_reviews, issue_rate, score}). */
    public record BetterScore(String dishUid, double avgRating, int totalReviews, double issueRate, double score) {
    }

    /** The scoring loop of {@code find_better_dish_for_issue} (pure; pinned against Python). */
    public static List<BetterScore> scoreBetterCandidates(List<String> candidateIds, Map<String, Integer> issueMap,
                                                        Map<String, RatingStats> ratingMap) {
        List<BetterScore> scored = new ArrayList<>();
        for (String cid : candidateIds) {
            RatingStats stats = ratingMap.get(cid);
            double avgRating = stats != null ? stats.avgRating() : 0.0;
            int totalReviews = stats != null ? stats.totalReviews() : 0;
            int issueCount = issueMap.getOrDefault(cid, 0);
            double issueRate = (double) issueCount / Math.max(totalReviews, 1);
            double popularity = Math.min(totalReviews / 100.0, 1.0);
            double score = (1.0 - issueRate) * 0.5 + (avgRating / 5.0) * 0.3 + popularity * 0.2;
            scored.add(new BetterScore(cid, avgRating, totalReviews, PyMath.round(issueRate, 3), PyMath.round(score, 4)));
        }
        scored.sort((a, b) -> b.score() > a.score() ? 1 : (b.score() < a.score() ? -1 : 0));
        return scored;
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Django {@code find_better_dish_for_issue}. PORT-NOTE (faithful): the issue match is a raw
     * case-insensitive substring match on {@code Review.issue} ({@code iexact | icontains}), NOT the
     * whitelist/normalisation used by the issue profile (RECOMMENDATION_FLOW.md §12); a user whose
     * latest review of the dish has no issue gets {@code items: []}; the source dish is discarded by
     * the raw path string (Django {@code candidate_ids.discard(str(dish_uid))}).
     */
    public BetterDishForIssueResponse findBetterDishForIssue(long userId, String dishUid, int limit) {
        UUID dishUuid = UUID.fromString(dishUid); // Django: invalid uuid -> ValidationError -> 500
        Dish dish = dishRepository.findActive(dishUuid).orElseThrow(DishNotFoundException::new);

        List<String> latest = jdbc.queryForList("""
                SELECT issue FROM review WHERE dish_uid = ? AND owner_id = ? AND deleted = FALSE
                ORDER BY created_at DESC LIMIT 1
                """, String.class, dishUuid, userId);
        String issue = latest.isEmpty() ? null : latest.get(0);
        if (issue == null || issue.isEmpty()) {
            log.debug("find_better_dish_for_issue: no issue in latest review user_id={} dish_uid={}", userId, dishUid);
            return new BetterDishForIssueResponse(null, dishUid, List.of());
        }

        List<Double> vector = vectorIndexService.getDishVector(dishUid);
        Set<String> candidateIds = new LinkedHashSet<>();
        if (vector != null && !vector.isEmpty()) {
            int n = Math.max(100, limit * 10);
            candidateIds.addAll(pipeline.newCandidateGenerator(n).vectorSourceIds(vector, List.of(), "", n));
        }

        if (candidateIds.size() < Math.max(10, limit * 2)) {
            String nameNoAccent = dish.getNameNoAccent() == null ? "" : dish.getNameNoAccent();
            List<String> words = new ArrayList<>();
            for (String w : nameNoAccent.strip().split("\\s+")) {
                if (w.length() > 2) {
                    words.add(w);
                }
            }
            words = words.subList(0, Math.min(3, words.size()));
            if (!words.isEmpty()) {
                StringBuilder sql = new StringBuilder(
                        "SELECT uid::text FROM dish WHERE deleted = FALSE AND category = ? AND (");
                List<Object> args = new ArrayList<>();
                args.add(dish.getCategory().name());
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) {
                        sql.append(" OR ");
                    }
                    sql.append("UPPER(name_no_accent) LIKE UPPER(?)");
                    args.add("%" + escapeLike(words.get(i)) + "%");
                }
                sql.append(") ORDER BY name LIMIT ?");
                args.add(Math.max(50, limit * 5));
                candidateIds.addAll(jdbc.queryForList(sql.toString(), String.class, args.toArray()));
            }
        }

        if (candidateIds.size() < Math.max(5, limit)) {
            candidateIds.addAll(jdbc.queryForList("""
                    SELECT uid::text FROM dish WHERE deleted = FALSE AND category = ? AND avg_rating >= 3.5
                    ORDER BY name LIMIT ?
                    """, String.class, dish.getCategory().name(), Math.max(100, limit * 10)));
        }

        if (candidateIds.isEmpty()) {
            candidateIds.addAll(jdbc.queryForList("""
                    SELECT uid::text FROM dish WHERE deleted = FALSE AND avg_rating >= 4.0
                    ORDER BY avg_rating DESC LIMIT ?
                    """, String.class, Math.max(50, limit * 5)));
        }

        candidateIds.remove(dishUid);
        log.debug("find_better_dish_for_issue: candidate count user_id={} dish_uid={} count={}", userId, dishUid,
                candidateIds.size());

        if (candidateIds.isEmpty()) {
            return new BetterDishForIssueResponse(issue, dishUid, popularDishFallbackItems(limit));
        }

        String[] ids = candidateIds.toArray(new String[0]);
        Map<String, Integer> issueMap = new HashMap<>();
        jdbc.query("""
                SELECT dish_uid::text AS dish_id, COUNT(uid) AS cnt FROM review
                WHERE dish_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                  AND (UPPER(issue) = UPPER(?) OR UPPER(issue) LIKE UPPER(?))
                GROUP BY dish_uid
                """, rs -> {
            issueMap.put(rs.getString("dish_id"), rs.getInt("cnt"));
        }, ids, issue, "%" + escapeLike(issue) + "%");

        Map<String, RatingStats> ratingMap = new HashMap<>();
        jdbc.query("""
                SELECT dish_uid::text AS dish_id, AVG(rating) AS avg_rating, COUNT(uid) AS total_reviews FROM review
                WHERE dish_uid = ANY(CAST(? AS uuid[])) AND deleted = FALSE
                GROUP BY dish_uid
                """, rs -> {
            Double avg = JdbcScoringDataSource.nullableDouble(rs, "avg_rating");
            ratingMap.put(rs.getString("dish_id"), new RatingStats(avg == null ? 0.0 : avg, rs.getInt("total_reviews")));
        }, (Object) ids);

        List<BetterScore> scored = scoreBetterCandidates(new ArrayList<>(candidateIds), issueMap, ratingMap);
        List<BetterScore> top = scored.subList(0, Math.min(Math.max(1, limit), scored.size()));
        if (top.isEmpty()) {
            return new BetterDishForIssueResponse(issue, dishUid, popularDishFallbackItems(limit));
        }

        Map<String, Dish> dishMap = new HashMap<>();
        dishRepository.findAllWithAttachment(top.stream().map(s -> UUID.fromString(s.dishUid())).toList())
                .forEach(d -> dishMap.put(d.getUid().toString(), d));

        List<BetterDishItem> items = new ArrayList<>();
        for (BetterScore s : top) {
            Dish d = dishMap.get(s.dishUid());
            if (d == null) {
                continue;
            }
            items.add(new BetterDishItem(s.dishUid(), d.getName(),
                    d.getAttachment() != null ? d.getAttachment().getPublicUrl() : null,
                    d.getPrice().doubleValue(), s.avgRating(), s.totalReviews(), s.issueRate(), s.score(),
                    "Lower " + issue + " incidence + better rating"));
        }
        if (items.isEmpty()) {
            return new BetterDishForIssueResponse(issue, dishUid, popularDishFallbackItems(limit));
        }
        return new BetterDishForIssueResponse(issue, dishUid, items);
    }

    /**
     * Django {@code _get_popular_dish_fallback_items}. {@code total_reviews} is always 0 there
     * ({@code hasattr(dish, "total_reviews")} is never true for a Dish) — reproduced.
     */
    List<BetterDishItem> popularDishFallbackItems(int limit) {
        List<BetterDishItem> items = new ArrayList<>();
        for (Dish d : dishRepository.findPopularFallback(PageRequest.of(0, Math.max(1, limit)))) {
            items.add(new BetterDishItem(d.getUid().toString(), d.getName(),
                    d.getAttachment() != null ? d.getAttachment().getPublicUrl() : null,
                    d.getPrice().doubleValue(), d.getAvgRating(), 0, 0.0,
                    PyMath.round(d.getAvgRating() / 5.0, 4), "Popular alternative"));
        }
        return items;
    }
}
