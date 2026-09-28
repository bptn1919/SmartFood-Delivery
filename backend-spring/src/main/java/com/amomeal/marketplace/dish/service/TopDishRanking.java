package com.amomeal.marketplace.dish.service;

/**
 * The "top dishes" Bayesian-average ranking formula, ported verbatim from the
 * annotation in ../../backend/dish/orm/dish.py::DishORM.get_top_dishes:
 *
 * <pre>
 * m = 50.0
 * system_avg_rating = Review.objects.filter(deleted=False).aggregate(Avg("rating")) or 0.0
 *
 * score = (review_count / (review_count + m)) * avg_rating
 *       + (m / (review_count + m)) * system_avg_rating
 * </pre>
 *
 * i.e. the classic Bayesian/IMDb-style shrinkage estimator: a dish with few
 * reviews is pulled toward the platform-wide average, and only converges to its
 * own {@code avg_rating} once it has many more than {@code m = 50} reviews. The
 * two weights always sum to 1, so the score stays on the rating scale.
 *
 * <p>Ordering is {@code -score, -sold_count, -review_count, name} (see
 * {@link DishService#getTopDishes}).
 *
 * <p>Worked examples (also asserted in {@code TopDishRankingTest}):
 * <ul>
 *   <li>{@code reviewCount=0} -&gt; score == systemAvg exactly, whatever the dish
 *       rating is (0/50 * r + 50/50 * sys).</li>
 *   <li>{@code reviewCount=50, avg=5.0, sys=3.0} -&gt; 0.5*5 + 0.5*3 = 4.0 (m is
 *       exactly the half-way point).</li>
 *   <li>{@code reviewCount=150, avg=5.0, sys=3.0} -&gt; 0.75*5 + 0.25*3 = 4.5.</li>
 * </ul>
 */
public final class TopDishRanking {

    private TopDishRanking() {
    }

    /**
     * Django: {@code m = 50.0} — the prior weight, in "number of reviews" units.
     * Not configurable in Django, so not configurable here either.
     */
    public static final double M = 50.0;

    /**
     * @param reviewCount      count of non-deleted reviews for this dish
     * @param avgRating        the dish row's denormalized {@code avg_rating}
     * @param systemAvgRating  AVG(rating) over all non-deleted reviews, 0.0 if none
     */
    public static double bayesianScore(long reviewCount, double avgRating, double systemAvgRating) {
        double n = reviewCount;
        return (n / (n + M)) * avgRating + (M / (n + M)) * systemAvgRating;
    }
}
