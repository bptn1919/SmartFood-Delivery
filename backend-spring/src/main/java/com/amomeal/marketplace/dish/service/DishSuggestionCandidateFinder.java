package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.dto.CandidateResponse;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientAlias;
import com.amomeal.marketplace.ingredient.repository.IngredientAliasRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.ingredient.service.SequenceMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Port of ../../backend/ingredient/orm/ingredient.py::IngredientORM
 * .find_suggestion_candidates, as consumed by
 * ../../backend/dish/services/__init__.py::preview_suggest_ingredient_for_dish
 * ("which existing USDA ingredient did this chef probably mean?").
 *
 * <h2>Why it lives in `dish` and not `ingredient`</h2>
 * The `ingredient` module already ported this cascade, but as a <i>private</i>
 * method of {@code IngredientService} (it only ever needed it internally, for
 * {@code searchIngredients}). Dish needs the raw candidate list, with the
 * {@code is_best} flag Django adds. Rather than modify the already-finished
 * `ingredient` module (out of scope for this port), this class re-runs the same
 * cascade against the same public {@code ingredient} repositories and the same
 * shared {@link SequenceMatcher}/{@link RemoveAccents} helpers — no logic is
 * duplicated by copy-paste guesswork, and no `ingredient` file is touched. Worth
 * consolidating into one shared, public ingredient-side method in a later pass.
 *
 * <h2>Scoring tiers (verbatim)</h2>
 * <pre>
 * alias exact  1.0     name exact    0.98
 * name prefix  0.9     alias prefix  0.88
 * fuzzy        0.5 + SequenceMatcher.ratio * 0.4   (only if ratio &gt;= 0.25)
 * </pre>
 * highest score per ingredient wins, ties broken by lowercased name, and the
 * top row is flagged {@code is_best}.
 *
 * <p>PORT-NOTE (same decision the `ingredient` port already made and flagged):
 * Django's fuzzy stage prefers a Postgres {@code pg_trgm} TrigramSimilarity
 * query and only falls back to Python's {@code difflib.SequenceMatcher} on a
 * non-Postgres vendor or a trigram error. This port always takes the
 * SequenceMatcher branch, so it does not depend on the {@code pg_trgm}
 * extension being installed; the Java {@code SequenceMatcher} was verified
 * against real CPython {@code difflib} output by that module's tests.
 */
@Service
@RequiredArgsConstructor
public class DishSuggestionCandidateFinder {

    /** Django slices the non-Postgres fallback at {@code [:200]}. */
    private static final int FUZZY_CANDIDATE_CAP = 200;

    /** Django: {@code if score >= 0.25} before rescaling. */
    private static final double FUZZY_THRESHOLD = 0.25;

    private final IngredientRepository ingredientRepository;
    private final IngredientAliasRepository aliasRepository;

    @Transactional(readOnly = true)
    public List<CandidateResponse> findCandidates(String suggestedName, int limit) {
        String normalizedQuery = RemoveAccents.apply(suggestedName == null ? "" : suggestedName)
                .strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        Map<UUID, Candidate> candidates = new LinkedHashMap<>();

        // STAGE 1: exact match (highest priority)
        for (IngredientAlias alias : aliasRepository.findExactActive(normalizedQuery, null)) {
            upsert(candidates, alias.getIngredient(), 1.0);
        }
        for (Ingredient ingredient : ingredientRepository.findExactByNameNoAccent(normalizedQuery, null)) {
            upsert(candidates, ingredient, 0.98);
        }

        // STAGE 2: prefix match
        for (Ingredient ingredient : ingredientRepository.findPrefixByNameNoAccent(normalizedQuery, null)) {
            upsert(candidates, ingredient, 0.9);
        }
        for (IngredientAlias alias : aliasRepository.findPrefixActive(normalizedQuery, null)) {
            upsert(candidates, alias.getIngredient(), 0.88);
        }

        // STAGE 3: fuzzy (see class PORT-NOTE)
        for (Ingredient ingredient : ingredientRepository.findCandidatesForFuzzyMatch(
                null, PageRequest.of(0, FUZZY_CANDIDATE_CAP))) {
            double ratio = SequenceMatcher.ratio(normalizedQuery, ingredient.getNameNoAccent());
            if (ratio >= FUZZY_THRESHOLD) {
                upsert(candidates, ingredient, 0.5 + ratio * 0.4);
            }
        }

        List<Candidate> sorted = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(c -> c.name().toLowerCase(Locale.ROOT)))
                .limit(Math.max(limit, 0))
                .toList();

        List<CandidateResponse> results = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Candidate c = sorted.get(i);
            // Django flags only results[0] as is_best.
            results.add(new CandidateResponse(c.uid(), c.name(), c.score(), i == 0));
        }
        return results;
    }

    private void upsert(Map<UUID, Candidate> map, Ingredient ingredient, double score) {
        double rounded = round4(score);
        Candidate current = map.get(ingredient.getUid());
        if (current == null || rounded > current.score()) {
            map.put(ingredient.getUid(), new Candidate(ingredient.getUid(), ingredient.getName(), rounded));
        }
    }

    /** Django: {@code round(float(score), 4)}. */
    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record Candidate(UUID uid, String name, double score) {
    }
}
