package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.dto.DishSearchResponse;
import com.amomeal.marketplace.dish.dto.DishSearchResultResponse;
import com.amomeal.marketplace.dish.entity.AllergyMode;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAlias;
import com.amomeal.marketplace.dish.entity.DishCategory;
import com.amomeal.marketplace.dish.entity.DishStatus;
import com.amomeal.marketplace.dish.repository.DishAliasRepository;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.DishIngredientRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.ingredient.entity.AllergicIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.ingredient.service.SequenceMatcher;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verbatim port of ../../backend/dish/services/search.py::DishSearchService.
 *
 * <p>Pipeline: Normalize -&gt; Retrieve (exact name, exact alias, fuzzy name,
 * fuzzy alias) -&gt; Semantic rank -&gt; allergy filter -&gt; Filter/format.
 *
 * <h2>Preserved quirks (PORT-NOTE, all real and all visible in the response)</h2>
 * <ol>
 *   <li><b>{@code FUZZY_THRESHOLD} is not applied.</b> Both fuzzy stages have
 *       their {@code if score >= cls.FUZZY_THRESHOLD:} line commented out in
 *       Django, so <i>every</i> non-deleted dish enters the candidate set for any
 *       non-empty query, with whatever similarity it scored (often ~0.0). The
 *       constant is kept here for documentation, and deliberately unused.</li>
 *   <li><b>Fuzzy-name and fuzzy-alias use different tie-breaking.</b> The
 *       name stage does a plain overwrite ("skip if already present, else set"),
 *       while the alias stage keeps the higher score — because the name stage
 *       already inserted every dish, the alias stage can only ever raise a
 *       score, never add a dish.</li>
 *   <li><b>The exact-alias stage does not filter deleted dishes.</b> Unlike the
 *       three other stages, {@code DishAlias.objects.filter(alias_name_no_accent
 *       __iexact=...)} has no {@code dish__deleted=False} guard, so an alias of a
 *       soft-deleted dish can surface it. Preserved (see
 *       {@link DishAliasRepository#findExactByAliasNoAccent}).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DishSearchService {

    /** Django: {@code FUZZY_THRESHOLD = 0.1} — see class PORT-NOTE 1, never applied. */
    static final double FUZZY_THRESHOLD = 0.1;

    /** Django: {@code RANK_WEIGHTS}. */
    static final double W_EXACT_NAME_MATCH = 1.0;
    static final double W_EXACT_ALIAS_MATCH = 0.95;
    static final double W_FUZZY_NAME_MATCH = 0.8;
    static final double W_FUZZY_ALIAS_MATCH = 0.75;
    static final double W_RATING_BOOST = 0.2;
    static final double W_POPULARITY_BOOST = 0.15;

    private final DishRepository dishRepository;
    private final DishAliasRepository dishAliasRepository;
    private final DishAvailabilityRepository availabilityRepository;
    private final DishIngredientRepository dishIngredientRepository;
    private final AllergicIngredientRepository allergicIngredientRepository;
    private final DishUserContext userContext;

    private enum MatchType {
        EXACT_NAME, EXACT_ALIAS, FUZZY_NAME, FUZZY_ALIAS
    }

    private record Candidate(Dish dish, double fuzzyScore, MatchType matchType) {
    }

    private record Ranked(Dish dish, double score) {
    }

    /** Django: {@code normalize(text)}. */
    static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return RemoveAccents.apply(text).toLowerCase(Locale.ROOT).strip();
    }

    /**
     * Django: {@code _calculate_fuzzy_score(query, target)}. Note the query is
     * already normalized by the caller, while the target is normalized here.
     */
    static double calculateFuzzyScore(String query, String target) {
        String targetNorm = normalize(target);
        if (query.equals(targetNorm)) {
            return 1.0;
        }
        return Math.max(0.0, SequenceMatcher.ratio(query, targetNorm));
    }

    /** Django: {@code retrieve_candidates(query)}. */
    @Transactional(readOnly = true)
    List<Candidate> retrieveCandidates(String queryNorm) {
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        if (queryNorm.isEmpty()) {
            return List.of();
        }

        // 1. Exact match on the main name.
        for (Dish dish : dishRepository.findAllByNameNoAccentIgnoreCaseAndDeletedFalse(queryNorm)) {
            candidates.put(dish.getUid(), new Candidate(dish, 1.0, MatchType.EXACT_NAME));
        }

        // 2. Exact match on aliases (see PORT-NOTE 3 — no deleted filter in Django).
        for (DishAlias alias : dishAliasRepository.findExactByAliasNoAccent(queryNorm)) {
            Dish dish = alias.getDish();
            Candidate existing = candidates.get(dish.getUid());
            if (existing == null || existing.fuzzyScore() < 0.98) {
                candidates.put(dish.getUid(), new Candidate(dish, 0.98, MatchType.EXACT_ALIAS));
            }
        }

        // 3. Fuzzy match on the main name — threshold intentionally not applied.
        for (Dish dish : dishRepository.findAllLiveWithRelations()) {
            if (candidates.containsKey(dish.getUid())) {
                continue; // skip anything that already has an exact match
            }
            double score = calculateFuzzyScore(queryNorm, dish.getNameNoAccent());
            candidates.put(dish.getUid(), new Candidate(dish, score, MatchType.FUZZY_NAME));
        }

        // 4. Fuzzy match on aliases.
        for (DishAlias alias : dishAliasRepository.findAllOfLiveDishes()) {
            UUID dishUid = alias.getDish().getUid();
            if (candidates.containsKey(dishUid)) {
                // Django: `continue` here — so in practice, because stage 3 inserted every
                // live dish, this stage never contributes anything. Preserved verbatim.
                continue;
            }
            double score = calculateFuzzyScore(queryNorm, alias.getAliasNameNoAccent());
            Candidate existing = candidates.get(dishUid);
            if (existing == null || existing.fuzzyScore() < score) {
                candidates.put(dishUid, new Candidate(alias.getDish(), score, MatchType.FUZZY_ALIAS));
            }
        }

        // Post-port suspension enforcement: DISH_LOCK-ed dishes never surface in customer search.
        candidates.values().removeIf(c -> c.dish().isSuspended());
        return new ArrayList<>(candidates.values());
    }

    /** Django: {@code semantic_rank(candidates, query)}. */
    static List<Ranked> semanticRank(List<Candidate> candidates) {
        List<Ranked> ranked = new ArrayList<>(candidates.size());

        for (Candidate candidate : candidates) {
            Dish dish = candidate.dish();
            double baseScore = switch (candidate.matchType()) {
                case EXACT_NAME -> W_EXACT_NAME_MATCH;
                case EXACT_ALIAS -> W_EXACT_ALIAS_MATCH;
                case FUZZY_NAME -> W_FUZZY_NAME_MATCH * candidate.fuzzyScore();
                case FUZZY_ALIAS -> W_FUZZY_ALIAS_MATCH * candidate.fuzzyScore();
            };

            // Boost from rating (0-5 -> 0-0.2). Django guards with `if dish.avg_rating`,
            // so a 0 rating contributes exactly 0.
            double ratingBoost = dish.getAvgRating() != 0
                    ? Math.min(W_RATING_BOOST, (dish.getAvgRating() / 5.0) * W_RATING_BOOST)
                    : 0;

            // Boost from final_score (popularity), same guard.
            double popularityBoost = dish.getFinalScore() != 0
                    ? Math.min(W_POPULARITY_BOOST, (dish.getFinalScore() / 100.0) * W_POPULARITY_BOOST)
                    : 0;

            ranked.add(new Ranked(dish, baseScore + ratingBoost + popularityBoost));
        }

        // Python's list.sort is stable, so equal scores keep insertion order.
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked;
    }

    /** Django: {@code search(...)} — the whole pipeline. */
    @Transactional(readOnly = true)
    public DishSearchResponse search(String query, CustomUser user, String category, Long locationId,
                                     String status, boolean availableToday, int limit) {
        // Step 1: normalize
        String queryNorm = normalize(query);
        if (queryNorm.isEmpty()) {
            return new DishSearchResponse(queryNorm, 0, List.of(), "Empty query");
        }

        // Step 2 + 3: retrieve + rank
        List<Ranked> ranked = semanticRank(retrieveCandidates(queryNorm));

        Set<UUID> favoriteIds = Set.of();
        AllergyMode allergyMode = AllergyMode.WARN;
        List<UUID> allergicIds = List.of();

        if (user != null) {
            allergyMode = userContext.allergyMode(user.getId());
            allergicIds = allergicIngredientRepository.findAllByUserAndDeletedFalse(user).stream()
                    .map(AllergicIngredient::getIngredient)
                    .map(Ingredient::getUid)
                    .toList();
            favoriteIds = userContext.favoriteDishUids(user.getId());
        }

        // Hide dishes containing allergic ingredients when mode is HIDE.
        if (user != null && allergyMode == AllergyMode.HIDE && !allergicIds.isEmpty() && !ranked.isEmpty()) {
            List<UUID> rankedIds = ranked.stream().map(r -> r.dish().getUid()).toList();
            Set<UUID> allergicDishIds = new HashSet<>(
                    dishIngredientRepository.findDishUidsContainingIngredients(rankedIds, allergicIds));
            ranked = ranked.stream().filter(r -> !allergicDishIds.contains(r.dish().getUid())).toList();
        }

        // Step 4: filter + format
        List<DishSearchResultResponse> results = filterResults(ranked, category, locationId, status,
                availableToday, limit, favoriteIds);

        return new DishSearchResponse(queryNorm, results.size(), results, null);
    }

    /** Django: {@code filter_results(...)}. */
    private List<DishSearchResultResponse> filterResults(List<Ranked> ranked, String category, Long locationId,
                                                         String status, boolean availableToday, int limit,
                                                         Set<UUID> favoriteIds) {
        List<DishSearchResultResponse> filtered = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Ranked entry : ranked) {
            Dish dish = entry.dish();

            // Filter by category
            if (StringUtils.hasText(category) && !matchesCategory(dish, category)) {
                continue;
            }

            // Filter by location (dish country, its subregion, or its region)
            if (locationId != null) {
                if (dish.getLocation() == null) {
                    continue;
                }
                boolean locationMatch = locationId.equals(dish.getLocation().getId())
                        || (dish.getLocation().getParent() != null
                            && locationId.equals(dish.getLocation().getParent().getId()))
                        || (dish.getLocation().getParent() != null
                            && dish.getLocation().getParent().getParent() != null
                            && locationId.equals(dish.getLocation().getParent().getParent().getId()));
                if (!locationMatch) {
                    continue;
                }
            }

            // Filter by status
            if (StringUtils.hasText(status) && !matchesStatus(dish, status)) {
                continue;
            }

            // Filter by availability today
            if (availableToday && !availabilityRepository.existsByDishAndAvailableDateAndAvailableTrue(dish, today)) {
                continue;
            }

            filtered.add(new DishSearchResultResponse(
                    dish.getUid().toString(),
                    dish.getName(),
                    dish.getCategory().name(),
                    dish.getPrice().doubleValue(),
                    dish.getDescription(),
                    dish.getStatus().name(),
                    dish.getAvgRating(),
                    dish.getFinalScore(),
                    // Django: round(float(score), 4)
                    Math.round(entry.score() * 10000.0) / 10000.0,
                    dish.getAttachment() == null ? null : dish.getAttachment().getPublicUrl(),
                    dish.getLocation() == null ? null : dish.getLocation().getId(),
                    favoriteIds.contains(dish.getUid())));
        }

        return filtered.size() > limit ? filtered.subList(0, limit) : filtered;
    }

    /**
     * Django compares the raw query-string against {@code dish.category}, which
     * is the enum's string value — an unknown value simply matches nothing.
     */
    private boolean matchesCategory(Dish dish, String category) {
        try {
            return dish.getCategory() == DishCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean matchesStatus(Dish dish, String status) {
        try {
            return dish.getStatus() == DishStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
