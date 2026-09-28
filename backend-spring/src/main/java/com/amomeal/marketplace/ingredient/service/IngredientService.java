package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.dto.*;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientAlias;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.amomeal.marketplace.ingredient.exception.IngredientDoesNotExistException;
import com.amomeal.marketplace.ingredient.exception.IngredientIsNotDeletedException;
import com.amomeal.marketplace.ingredient.exception.IngredientIsReferencedException;
import com.amomeal.marketplace.ingredient.exception.IngredientNameAlreadyExistsException;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.FavouriteIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientAliasRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientSuggestionRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mirrors ../../backend/ingredient/services/__init__.py's
 * {@code IngredientQueryService}/{@code IngredientCommandService} (merged
 * into one Java service — Django splits these across two classes purely for
 * its own internal composition, not for a behavioral reason worth mirroring
 * 1:1 in Java) plus the alias-creation and search/autocomplete algorithms
 * from {@code ../../backend/ingredient/orm/ingredient.py::IngredientORM}.
 *
 * <p>PORT-NOTE (fuzzy search): Django's {@code find_suggestion_candidates}
 * branches on {@code connection.vendor == "postgresql"} to use the
 * {@code pg_trgm} extension's {@code TrigramSimilarity}; otherwise it falls
 * back to {@code difflib.SequenceMatcher}-based scoring. This port always
 * takes the fallback-branch algorithm (see {@link SequenceMatcher}) rather
 * than taking a hard dependency on the pg_trgm Postgres extension — same
 * scoring formula/thresholds Django's own fallback branch uses, just applied
 * unconditionally. Flagged in PROGRESS.md; revisit if trigram-quality fuzzy
 * ranking turns out to matter for a real ingredient catalog size.
 */
@Service
@RequiredArgsConstructor
public class IngredientService {

    private static final int FUZZY_CANDIDATE_CAP = 200;
    private static final double FUZZY_THRESHOLD = 0.25;

    private final IngredientRepository ingredientRepository;
    private final IngredientAliasRepository aliasRepository;
    private final IngredientSuggestionRepository suggestionRepository;
    private final FavouriteIngredientRepository favouriteIngredientRepository;
    private final AllergicIngredientRepository allergicIngredientRepository;
    private final CustomUserRepository customUserRepository;

    // ===================================================================
    // CRUD
    // ===================================================================

    @Transactional
    public IngredientResponse createNewIngredient(Long userId, IngredientRequest payload) {
        String name = normalizeSpaces(payload.name());
        if (ingredientRepository.existsByNameIgnoreCaseAndDeletedFalse(name)) {
            throw new IngredientNameAlreadyExistsException();
        }
        CustomUser user = customUserRepository.getReferenceById(userId);
        Ingredient ingredient = applyRequest(Ingredient.builder().build(), payload);
        ingredient.setName(name);
        ingredient.setOwner(user);
        ingredient.setUpdater(user);
        ingredient = ingredientRepository.save(ingredient);
        return IngredientResponse.from(ingredient);
    }

    @Transactional(readOnly = true)
    public PageResponse<IngredientResponse> getAllIngredients(
            String search, String categories, String orderBy, String sortType, int page, int pageSize) {
        Specification<Ingredient> spec = IngredientSpecifications.filter(search, categories);
        Pageable pageable = toPageable(page, pageSize, resolveIngredientSort(orderBy, sortType));
        var result = ingredientRepository.findAll(spec, pageable);
        List<IngredientResponse> content = result.getContent().stream().map(IngredientResponse::from).toList();
        return PageResponse.of(content, page, pageSize, result.getTotalElements());
    }

    /**
     * Mirrors IngredientQueryService.get_all_for_chef: USDA ingredients + the
     * chef's own PENDING suggestions, deduped by lowercased name (USDA wins
     * ties since it's appended first).
     *
     * <p>PORT-NOTE (preserved quirk, not fixed): Django's final sort key does
     * {@code getattr(item, "energy", 0) if hasattr(item, "energy") else 0} on
     * plain {@code dict} rows — {@code hasattr(dict, "energy")} is always
     * {@code False}, so this branch is dead and the "energy" sort key is
     * always 0 for every item, regardless of {@code order_by}. Combined with
     * Python's stable sort, the net effect is: when {@code order_by=="energy"},
     * the final order is just each source's already-fetched order (USDA rows
     * pre-sorted by {@code -energy} at the DB level, suggestions in fetch
     * order) grouped by source. Replicated exactly below via a stable sort on
     * source-priority only.
     */
    @Transactional(readOnly = true)
    public PageResponse<IngredientSearchItem> getAllIngredientsForChef(
            Long userId, String search, String categories, String orderBy, String sortType, int page, int pageSize) {
        CustomUser user = customUserRepository.getReferenceById(userId);

        Specification<Ingredient> spec = IngredientSpecifications.filter(search, categories);
        Sort usdaSort = "energy".equals(orderBy) ? Sort.by(Sort.Direction.DESC, "energy")
                : Sort.by(Sort.Direction.DESC, "updatedAt");
        List<Ingredient> usdaItems = ingredientRepository.findAll(spec, PageRequest.of(0, 100, usdaSort)).getContent();

        List<IngredientSuggestion> suggestions = suggestionRepository.findAll(
                IngredientSuggestionSpecifications.filter(IngredientImportStatus.PENDING, null, null, user),
                PageRequest.of(0, 100)).getContent();
        if (StringUtils.hasText(categories)) {
            Set<String> cats = Arrays.stream(categories.split(","))
                    .map(String::trim).filter(StringUtils::hasText).map(s -> s.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            suggestions = suggestions.stream()
                    .filter(s -> s.getSuggestedCategory() != null && cats.contains(s.getSuggestedCategory().name()))
                    .toList();
        }

        record Row(IngredientSearchItem item, int sourcePriority) {
        }
        List<Row> rows = new ArrayList<>();
        for (Ingredient i : usdaItems) {
            rows.add(new Row(new IngredientSearchItem(i.getUid(), null, i.getName(), i.getCategory(), null, 1.0,
                    "USDA", "APPROVED", false), 0));
        }
        for (IngredientSuggestion s : suggestions) {
            if (s.getSuggestedCategory() == null) {
                continue; // Django: "tránh crash enum"
            }
            rows.add(new Row(new IngredientSearchItem(null, s.getUid(), s.getSuggestedName(), s.getSuggestedCategory(),
                    null, 0.7, "CHEF_SUGGESTION", s.getStatus().name(), true), 1));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Row> deduped = new ArrayList<>();
        for (Row r : rows) {
            String key = r.item().name().strip().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                deduped.add(r);
            }
        }

        List<IngredientSearchItem> sorted;
        if ("energy".equals(orderBy)) {
            sorted = deduped.stream()
                    .sorted((a, b) -> Integer.compare(a.sourcePriority(), b.sourcePriority()))
                    .map(Row::item).toList();
        } else {
            sorted = deduped.stream()
                    .sorted((a, b) -> {
                        int cmp = Integer.compare(a.sourcePriority(), b.sourcePriority());
                        if (cmp != 0) return cmp;
                        return a.item().name().toLowerCase(Locale.ROOT).compareTo(b.item().name().toLowerCase(Locale.ROOT));
                    })
                    .map(Row::item).toList();
        }

        int from = Math.min((Math.max(page, 1) - 1) * pageSize, sorted.size());
        int to = Math.min(from + pageSize, sorted.size());
        return PageResponse.of(sorted.subList(from, to), page, pageSize, sorted.size());
    }

    @Transactional(readOnly = true)
    public IngredientResponse getIngredientResponseByUid(UUID uid) {
        return IngredientResponse.from(getByUidOrThrow(uid));
    }

    public Ingredient getByUidOrThrow(UUID uid) {
        return ingredientRepository.findByUidAndDeletedFalse(uid).orElseThrow(IngredientDoesNotExistException::new);
    }

    @Transactional
    public IngredientResponse updateIngredient(Long userId, UUID uid, IngredientRequest payload) {
        Ingredient ingredient = getByUidOrThrow(uid);
        applyRequest(ingredient, payload);
        ingredient.setUpdater(customUserRepository.getReferenceById(userId));
        ingredient = ingredientRepository.save(ingredient);
        return IngredientResponse.from(ingredient);
    }

    @Transactional
    public boolean softDeleteIngredient(Long userId, UUID uid) {
        Ingredient ingredient = getByUidOrThrow(uid);
        if (hasRelatedObjects(ingredient)) {
            throw new IngredientIsReferencedException();
        }
        ingredient.setDeleted(true);
        ingredient.setUpdater(customUserRepository.getReferenceById(userId));
        ingredientRepository.save(ingredient);
        return true;
    }

    @Transactional
    public boolean hardDeleteIngredient(UUID uid) {
        Ingredient ingredient = getByUidOrThrow(uid);
        try {
            ingredientRepository.delete(ingredient);
            ingredientRepository.flush();
            return true;
        } catch (DataIntegrityViolationException e) {
            throw new IngredientIsReferencedException();
        }
    }

    /**
     * PORT-NOTE (preserved bug, not fixed): Django's restore_ingredient looks
     * the ingredient up via {@code IngredientQueryService.get_by_uid}, which
     * filters {@code deleted=False} — so a genuinely soft-deleted ingredient
     * (the only case restore is meant for) always 404s as
     * {@code IngredientDoesNotExist} before Django's own
     * {@code if not ingredient.deleted: raise IngredientIsNotDeleted} check
     * is ever reached, and a non-deleted ingredient (found by that same
     * filter) always fails that check instead. Net effect: Django's restore
     * endpoint can never actually succeed today. Replicated exactly here
     * (same {@code getByUidOrThrow}, deleted=false filter) rather than
     * "fixed" to something that would actually restore — flag for the user
     * if real restore behavior is wanted (the fix would be looking the
     * ingredient up WITHOUT the deleted=false filter).
     */
    @Transactional
    public boolean restoreIngredient(Long userId, UUID uid) {
        Ingredient ingredient = getByUidOrThrow(uid);
        if (!ingredient.isDeleted()) {
            throw new IngredientIsNotDeletedException();
        }
        ingredient.setDeleted(false);
        ingredient.setUpdater(customUserRepository.getReferenceById(userId));
        ingredientRepository.save(ingredient);
        return true;
    }

    /**
     * Mirrors has_related_objects(instance=ingredient, exclude=["attachment"])
     * (../../backend/utils/functions/check_relation.py) restricted to the
     * relations that exist in this port so far (aliases/favourites/allergies/
     * suggestions). PORT-NOTE: Django's version also walks {@code dish}'s
     * {@code DishIngredient} reverse FK — `dish` isn't ported yet (ingredient
     * ports first per CLAUDE.md §7), so an ingredient referenced only by a
     * (future) dish's ingredient list will NOT be blocked from soft-delete by
     * this port today. Flagged in PROGRESS.md; revisit once `dish` exists.
     */
    private boolean hasRelatedObjects(Ingredient ingredient) {
        return aliasRepository.existsByIngredient(ingredient)
                || favouriteIngredientRepository.existsByIngredient(ingredient)
                || allergicIngredientRepository.existsByIngredient(ingredient)
                || suggestionRepository.existsByIngredient(ingredient);
    }

    private Ingredient applyRequest(Ingredient target, IngredientRequest req) {
        target.setName(req.name());
        target.setCategory(req.category());
        target.setWeight(req.weight());
        target.setEnergy(req.energy());
        target.setProtein(req.protein());
        target.setLipid(req.lipid());
        target.setCarbohydrate(req.carbohydrate());
        target.setFiber(req.fiber());
        target.setNatri(req.natri());
        target.setKali(req.kali());
        target.setCholesterol(req.cholesterol());
        target.setRetinol(req.retinol());
        target.setCaroten(req.caroten());
        target.setVitaminB1(req.vitaminB1());
        target.setVitaminB2(req.vitaminB2());
        target.setVitaminPp(req.vitaminPp());
        target.setVitaminC(req.vitaminC());
        target.setCalcium(req.calcium());
        target.setPhosphorus(req.phosphorus());
        target.setFe(req.fe());
        target.setMg(req.mg());
        target.setZn(req.zn());
        if (req.source() != null) {
            target.setSource(req.source());
        }
        return target;
    }

    // ===================================================================
    // Aliases
    // ===================================================================

    @Transactional
    public IngredientAliasResponse createAlias(Long userId, IngredientAliasCreateRequest req) {
        Ingredient ingredient = getByUidOrThrow(req.ingredientUid());
        String aliasText = req.alias() == null ? "" : req.alias().strip();
        if (!StringUtils.hasText(aliasText)) {
            throw new IllegalArgumentException("alias is required");
        }
        IngredientAlias alias = upsertAlias(ingredient, aliasText, customUserRepository.getReferenceById(userId));
        return IngredientAliasResponse.from(alias);
    }

    /** Mirrors IngredientORM.create_ingredient_alias's update_or_create-by-alias_no_accent semantics. */
    IngredientAlias upsertAlias(Ingredient ingredient, String aliasText, CustomUser user) {
        String normalized = RemoveAccents.apply(aliasText);
        IngredientAlias alias = aliasRepository.findByAliasNoAccent(normalized).orElseGet(IngredientAlias::new);
        alias.setIngredient(ingredient);
        alias.setAlias(aliasText);
        alias.setCreatedBy(user);
        alias.setActive(true);
        return aliasRepository.save(alias);
    }

    @Transactional(readOnly = true)
    public List<IngredientAliasResponse> listAliases(String search) {
        List<IngredientAlias> aliases = StringUtils.hasText(search)
                ? aliasRepository.findAllByAliasNoAccentContainingOrderByAliasAsc(RemoveAccents.apply(search))
                : aliasRepository.findAllByOrderByAliasAsc();
        return aliases.stream().map(IngredientAliasResponse::from).toList();
    }

    // ===================================================================
    // Search / autocomplete
    // ===================================================================

    @Transactional(readOnly = true)
    public List<IngredientSearchItem> searchIngredients(CustomUser user, String query, String categories, int limit) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        int fetchLimit = limit > 0 ? limit * 2 : 1000;
        IngredientCategory category = parseCategoryOrNull(categories);

        List<Candidate> usdaResults = findSuggestionCandidates(trimmed, fetchLimit, category);
        List<IngredientSuggestion> suggestions = findUserPendingSuggestions(user, trimmed, fetchLimit, category);

        record Row(String dedupName, String source, IngredientSearchItem item) {
        }
        List<Row> rows = new ArrayList<>();
        for (Candidate c : usdaResults) {
            rows.add(new Row(c.name().strip().toLowerCase(Locale.ROOT), "USDA",
                    new IngredientSearchItem(c.uid(), null, c.name(), c.category(), c.matchedAlias(), c.score(),
                            "USDA", "APPROVED", false)));
        }
        for (IngredientSuggestion s : suggestions) {
            double score = computeSuggestionScore(trimmed, s.getSuggestedName());
            rows.add(new Row(s.getSuggestedName().strip().toLowerCase(Locale.ROOT), "CHEF_SUGGESTION",
                    new IngredientSearchItem(null, s.getUid(), s.getSuggestedName(), s.getSuggestedCategory(), null,
                            round4(score), "CHEF_SUGGESTION", s.getStatus().name(), true)));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<IngredientSearchItem> deduped = new ArrayList<>();
        for (Row r : rows) {
            if (seen.add(r.dedupName() + " " + r.source())) {
                deduped.add(r.item());
            }
        }
        return deduped.size() > limit ? deduped.subList(0, limit) : deduped;
    }

    private double computeSuggestionScore(String query, String name) {
        double base = SequenceMatcher.ratio(query.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
        return 0.55 + base * 0.25;
    }

    private List<IngredientSuggestion> findUserPendingSuggestions(CustomUser user, String query, int limit, IngredientCategory category) {
        String normalizedQuery = RemoveAccents.apply(query == null ? "" : query).strip().toLowerCase(Locale.ROOT);
        Specification<IngredientSuggestion> spec = IngredientSuggestionSpecifications.filter(
                IngredientImportStatus.PENDING, normalizedQuery, category, user);
        return suggestionRepository.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Transactional(readOnly = true)
    public List<IngredientAutocompleteItem> autocomplete(String query, int limit) {
        String rawQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String normalizedQuery = RemoveAccents.apply(rawQuery).strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        List<Ingredient> ingredients = ingredientRepository.findAllByDeletedFalse();
        Map<UUID, List<IngredientAlias>> aliasesByIngredient = aliasRepository
                .findAllByActiveTrueAndIngredientDeletedFalseAndIngredientIn(ingredients).stream()
                .collect(Collectors.groupingBy(a -> a.getIngredient().getUid()));

        record Scored(Ingredient ingredient, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            double best = scoreLabel(ingredient.getName(), rawQuery, normalizedQuery);
            for (IngredientAlias alias : aliasesByIngredient.getOrDefault(ingredient.getUid(), List.of())) {
                best = Math.max(best, scoreLabel(alias.getAlias(), rawQuery, normalizedQuery));
            }
            if (best >= FUZZY_THRESHOLD) {
                scored.add(new Scored(ingredient, best));
            }
        }
        return scored.stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(b.score(), a.score());
                    return cmp != 0 ? cmp : a.ingredient().getName().toLowerCase(Locale.ROOT)
                            .compareTo(b.ingredient().getName().toLowerCase(Locale.ROOT));
                })
                .limit(Math.max(limit, 0))
                .map(s -> new IngredientAutocompleteItem(s.ingredient().getUid(), s.ingredient().getName(), s.ingredient().getCategory()))
                .toList();
    }

    /** Shared scoring cascade used by both autocomplete and (indirectly) the fuzzy fallback text. */
    private double scoreLabel(String label, String rawQuery, String normalizedQuery) {
        String labelRaw = label == null ? "" : label.strip().toLowerCase(Locale.ROOT);
        String labelNorm = RemoveAccents.apply(labelRaw).strip().toLowerCase(Locale.ROOT);

        if (labelRaw.equals(rawQuery)) return 1.0;
        if (labelNorm.equals(normalizedQuery)) return 0.995;
        if (labelRaw.startsWith(rawQuery)) return 0.99;
        if (labelNorm.startsWith(normalizedQuery)) return 0.93;
        if (labelNorm.contains(normalizedQuery)) return 0.9;
        return SequenceMatcher.ratio(normalizedQuery, labelNorm) * 0.8;
    }

    private record Candidate(UUID uid, String name, IngredientCategory category, String matchedAlias, double score) {
    }

    /** Mirrors IngredientORM.find_suggestion_candidates — see class javadoc PORT-NOTE re: fuzzy stage. */
    private List<Candidate> findSuggestionCandidates(String suggestedName, int limit, IngredientCategory category) {
        String normalizedQuery = RemoveAccents.apply(suggestedName == null ? "" : suggestedName).strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        Map<UUID, Candidate> candidates = new LinkedHashMap<>();

        // Stage 1: exact match
        for (IngredientAlias alias : aliasRepository.findExactActive(normalizedQuery, category)) {
            upsert(candidates, alias.getIngredient(), 1.0, alias.getAlias());
        }
        for (Ingredient ingredient : ingredientRepository.findExactByNameNoAccent(normalizedQuery, category)) {
            upsert(candidates, ingredient, 0.98, null);
        }

        // Stage 2: prefix match
        for (Ingredient ingredient : ingredientRepository.findPrefixByNameNoAccent(normalizedQuery, category)) {
            upsert(candidates, ingredient, 0.9, null);
        }
        for (IngredientAlias alias : aliasRepository.findPrefixActive(normalizedQuery, category)) {
            upsert(candidates, alias.getIngredient(), 0.88, alias.getAlias());
        }

        // Stage 3: fuzzy fallback (see class javadoc PORT-NOTE)
        for (Ingredient ingredient : ingredientRepository.findCandidatesForFuzzyMatch(category, PageRequest.of(0, FUZZY_CANDIDATE_CAP))) {
            double score = SequenceMatcher.ratio(normalizedQuery, ingredient.getNameNoAccent());
            if (score >= FUZZY_THRESHOLD) {
                upsert(candidates, ingredient, 0.5 + score * 0.4, null);
            }
        }

        return candidates.values().stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(b.score(), a.score());
                    return cmp != 0 ? cmp : a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT));
                })
                .limit(Math.max(limit, 0))
                .toList();
    }

    private void upsert(Map<UUID, Candidate> map, Ingredient ingredient, double score, String matchedAlias) {
        Candidate current = map.get(ingredient.getUid());
        double rounded = round4(score);
        if (current == null || rounded > current.score()) {
            map.put(ingredient.getUid(), new Candidate(ingredient.getUid(), ingredient.getName(), ingredient.getCategory(), matchedAlias, rounded));
        }
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /**
     * PORT-NOTE: Django passes {@code filter.categories} (a possibly
     * comma-separated string on the /search endpoint's
     * FilterCategoryIngredientSchema) straight into an equality filter
     * ({@code .filter(category=category)}), not a {@code category__in} split —
     * a real quirk (multi-category search silently matches nothing). A
     * single valid category string still works. This port mirrors the
     * single-value case and, for an unparseable value (blank/multi-value),
     * falls back to "no category filter" rather than Django's "matches
     * nothing" — a minor, deliberate simplification (crashing on an invalid
     * enum string would be worse than the two behaviors differing on this
     * edge case).
     */
    private IngredientCategory parseCategoryOrNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return IngredientCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Sort resolveIngredientSort(String orderBy, String sortType) {
        String field = "energy".equals(orderBy) ? "energy" : "updatedAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortType) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }

    static Pageable toPageable(int page, int pageSize, Sort sort) {
        int safePage = Math.max(page, 1) - 1;
        int safeSize = pageSize > 0 ? pageSize : 50;
        return PageRequest.of(safePage, safeSize, sort);
    }

    private static String normalizeSpaces(String value) {
        return String.join(" ", value.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }
}
