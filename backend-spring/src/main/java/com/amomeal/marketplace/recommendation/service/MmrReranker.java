package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.common.util.PyMath;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 1:1 port of ../../backend/recommendation/services/rerank.py::MMRReranker (greedy Maximal
 * Marginal Relevance):
 * <pre>mmr = λ·relevance − (1−λ)·max_cosine(item, already selected)</pre>
 *
 * <p>λ is clamped to [0, 1]. Cosine is pure-Java by default; when
 * {@code use_pgvector_similarity} is on, {@code pgvectorSimilarity} ({@code SELECT 1 - (a <=> b)})
 * is tried first and a failure (null) permanently falls back to the Python-equivalent formula for
 * this reranker instance — exactly Django's {@code _pgvector_unavailable} latch. Pinned against
 * the Python code (fixture key {@code mmr}).
 */
public class MmrReranker {

    private final double lambdaValue;
    private final boolean usePgvector;
    /** Returns null when pgvector is unavailable (Django: returns None + latches). */
    private final BiFunction<List<Double>, List<Double>, Double> pgvectorSimilarity;
    private boolean pgvectorUnavailable = false;

    public MmrReranker(double lambdaValue, boolean usePgvector,
                       BiFunction<List<Double>, List<Double>, Double> pgvectorSimilarity) {
        this.lambdaValue = Math.max(0.0, Math.min(lambdaValue, 1.0));
        this.usePgvector = usePgvector;
        this.pgvectorSimilarity = pgvectorSimilarity;
    }

    public MmrReranker(double lambdaValue) {
        this(lambdaValue, false, null);
    }

    /** Django {@code _cosine_similarity_python} — identical to the scoring cosine. */
    public static double cosineSimilarityPython(List<Double> v1, List<Double> v2) {
        return ScoringEngine.cosineSimilarity(v1, v2);
    }

    private double cosineSimilarity(List<Double> v1, List<Double> v2) {
        if (usePgvector) {
            Double similarity = cosineSimilarityPgvector(v1, v2);
            if (similarity != null) {
                return similarity;
            }
        }
        return cosineSimilarityPython(v1, v2);
    }

    private Double cosineSimilarityPgvector(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.isEmpty() || v2.isEmpty()) {
            return 0.0;
        }
        if (pgvectorUnavailable || pgvectorSimilarity == null) {
            return null;
        }
        Double result = pgvectorSimilarity.apply(v1, v2);
        if (result == null) {
            pgvectorUnavailable = true;
        }
        return result;
    }

    public List<ScoredItem> rerank(List<ScoredItem> scoredItems, int take) {
        if (take <= 0 || scoredItems == null || scoredItems.isEmpty()) {
            return new ArrayList<>();
        }
        List<ScoredItem> candidates = new ArrayList<>(scoredItems);
        List<ScoredItem> selected = new ArrayList<>();
        while (!candidates.isEmpty() && selected.size() < take) {
            int bestIndex = 0;
            double bestMmrScore = -1e9;
            for (int idx = 0; idx < candidates.size(); idx++) {
                ScoredItem item = candidates.get(idx);
                double relevance = item.getScore();
                List<Double> itemVec = item.getDishVector() == null ? List.of() : item.getDishVector();
                double maxSimilarity = 0.0;
                if (!selected.isEmpty()) {
                    List<Double> sims = new ArrayList<>(selected.size());
                    for (ScoredItem s : selected) {
                        sims.add(cosineSimilarity(itemVec, s.getDishVector() == null ? List.of() : s.getDishVector()));
                    }
                    maxSimilarity = PyMath.pyMax(sims);
                }
                double mmrScore = lambdaValue * relevance - (1.0 - lambdaValue) * maxSimilarity;
                if (mmrScore > bestMmrScore) {
                    bestMmrScore = mmrScore;
                    bestIndex = idx;
                }
            }
            selected.add(candidates.remove(bestIndex));
        }
        return selected;
    }
}
