package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.dish.entity.Dish;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * One entry of {@code ScoringEngine.score(...)}'s output list (a dict in Django). All numeric
 * components are already rounded to 6 decimals exactly like Django; {@code dishVector} /
 * {@code userVector} are the raw (unrounded) vectors, used by MMR.
 *
 * <p>Fields default to 0 so {@link Explain#buildReasons} behaves like Django's
 * {@code item.get(key, 0.0)} for partially-populated items.
 */
@Getter
@Setter
public class ScoredItem {
    private Dish dish;
    private String dishId;
    private double score;
    private double baseScore;
    private double favoriteScore;
    private double historyScore;
    private double issuePenalty;
    /** Django key {@code preference_nutrition_mismatch_penalty}; null = key absent (falls back to nutritionPenalty). */
    private Double preferenceNutritionMismatchPenalty;
    /** Legacy key {@code nutrition_penalty} — only read by build_reasons as a fallback. */
    private double nutritionPenalty;
    private double nutritionConfidence;
    private double penaltyConfidence;
    private double dietAlignment;
    private double ingredientMatchRatio;
    private List<Double> dishVector = List.of();
    private List<Double> userVector = List.of();
}
