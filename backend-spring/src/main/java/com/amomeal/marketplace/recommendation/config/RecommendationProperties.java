package com.amomeal.marketplace.recommendation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors ../../backend/marketplace/settings.py::RECOMMENDATION_CONFIG key for key, with the same
 * defaults and the same environment variable names (bound in application.yml, e.g.
 * {@code RECOMMENDATION_HISTORY_DECAY_LAMBDA}).
 *
 * <p>{@code prioritizeUnorderedDishes}: Django's pipeline reads
 * {@code config.get("prioritize_unordered_dishes", False)} but settings.py never defines the key
 * (no env var either) — so it is always False there. Kept as a plain property (default false, no
 * env binding) to stay equivalent.
 *
 * <p>{@code dishMatchThreshold}/{@code preserveDishMatchNoThreshold}: user-decided fix (CLAUDE.md
 * §0.6) for {@code DailyNutritionService#matchDish} (Django {@code _match_dish}). Django takes the
 * #1 {@code DishSearchService.search(..., limit=1)} hit with no floor, so parse-meal tiers 2-4
 * (translation mapping / USDA ingredient / Gemini recipe) are unreachable whenever any live dish
 * exists at all, however unrelated. Default ({@code preserveDishMatchNoThreshold=false}) gates the
 * match on the search hit's own {@code search_score} (the same weighted fuzzy/exact + rating +
 * popularity score {@code DishSearchService} already computes and returns — no new metric) being
 * &gt;= {@code dishMatchThreshold} (NUTRITION_API.md: 0.6); {@code true} reproduces Django's
 * always-take-the-top-hit behavior. Does not touch {@code DishSearchService}/its endpoint.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.recommendation")
public class RecommendationProperties {

    private int candidatePoolSize = 300;
    private int candidateMaxPerCategory = 0;
    private boolean usePgvectorAnnCandidates = false;
    private double historyDecayLambda = 0.03;
    private double recencyDecayLambda = 0.02;
    private double favoriteRecencyDecayLambda = 0.03;
    private double userVectorEmaAlpha = 0.35;
    private List<Double> dietBalancedTarget = new ArrayList<>(List.of(0.3, 0.3, 0.4));
    private double mmrLambda = 0.5;
    private boolean usePgvectorSimilarity = false;
    private double penaltyGamma = 0.25;
    private double penaltyCorrelationThreshold = 0.35;
    private boolean prioritizeUnorderedDishes = false;
    private double dishMatchThreshold = 0.6;
    private boolean preserveDishMatchNoThreshold = false;
    private Weights weights = new Weights();

    @Getter
    @Setter
    public static class Weights {
        private double w1 = 0.4;
        private double w2 = 0.2;
        private double w3 = 0.2;
        private double w4 = 0.1;
        private double w5 = 0.1;

        /** Ordered like Django's dict literal (w1..w5) — echoed in the feed's {@code meta.weights}. */
        public Map<String, Double> asMap() {
            Map<String, Double> map = new LinkedHashMap<>();
            map.put("w1", w1);
            map.put("w2", w2);
            map.put("w3", w3);
            map.put("w4", w4);
            map.put("w5", w5);
            return map;
        }
    }
}
