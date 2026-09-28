package com.amomeal.marketplace.dish.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the "top dishes" Bayesian ranking formula ported from
 * ../../backend/dish/orm/dish.py::DishORM.get_top_dishes against
 * hand-computed values (not just "it runs"). m = 50.0.
 *
 * <pre>
 * score = (n / (n + 50)) * avg_rating + (50 / (n + 50)) * system_avg
 * </pre>
 */
class TopDishRankingTest {

    private static final double EPS = 1e-9;

    @Test
    void mIsFifty_matchingDjango() {
        assertThat(TopDishRanking.M).isEqualTo(50.0);
    }

    @Test
    void zeroReviews_collapsesToSystemAverage() {
        // 0/(0+50) * 5.0 + 50/(0+50) * 3.7 = 0 + 3.7
        assertThat(TopDishRanking.bayesianScore(0, 5.0, 3.7)).isCloseTo(3.7, within());
        // ...regardless of the dish's own (meaningless, review-less) avg_rating
        assertThat(TopDishRanking.bayesianScore(0, 0.0, 3.7)).isCloseTo(3.7, within());
    }

    @Test
    void reviewCountEqualToM_isTheExactHalfwayPoint() {
        // 50/100 * 5.0 + 50/100 * 3.0 = 2.5 + 1.5 = 4.0
        assertThat(TopDishRanking.bayesianScore(50, 5.0, 3.0)).isCloseTo(4.0, within());
    }

    @Test
    void reviewCountThreeTimesM_weightsDishRatingAtThreeQuarters() {
        // 150/200 * 5.0 + 50/200 * 3.0 = 3.75 + 0.75 = 4.5
        assertThat(TopDishRanking.bayesianScore(150, 5.0, 3.0)).isCloseTo(4.5, within());
    }

    @Test
    void lowRatedDishWithFewReviewsIsPulledUp_highRatedWithManyIsNot() {
        // A 1-review 5.0 dish must NOT outrank a 500-review 4.8 dish when the
        // platform average is 4.0 — that shrinkage is the entire point of the prior.
        double newcomer = TopDishRanking.bayesianScore(1, 5.0, 4.0);   // 1/51*5 + 50/51*4
        double established = TopDishRanking.bayesianScore(500, 4.8, 4.0); // 500/550*4.8 + 50/550*4

        assertThat(newcomer).isCloseTo(1.0 / 51 * 5.0 + 50.0 / 51 * 4.0, within());
        assertThat(established).isCloseTo(500.0 / 550 * 4.8 + 50.0 / 550 * 4.0, within());
        assertThat(newcomer).isLessThan(established);
    }

    @Test
    void weightsAlwaysSumToOne_soScoreStaysOnTheRatingScale() {
        // With avg == system average, the score must equal that value exactly for any n.
        for (long n : new long[]{0, 1, 7, 50, 999}) {
            assertThat(TopDishRanking.bayesianScore(n, 4.25, 4.25)).isCloseTo(4.25, within());
        }
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(EPS);
    }
}
