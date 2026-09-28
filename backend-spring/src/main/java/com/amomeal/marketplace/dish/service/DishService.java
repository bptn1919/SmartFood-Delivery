package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.service.AttachmentService;
import com.amomeal.marketplace.dish.dto.*;
import com.amomeal.marketplace.dish.entity.*;
import com.amomeal.marketplace.dish.exception.*;
import com.amomeal.marketplace.dish.repository.*;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.ingredient.entity.AllergicIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientImportStatus;
import com.amomeal.marketplace.ingredient.entity.IngredientSource;
import com.amomeal.marketplace.ingredient.entity.IngredientSuggestion;
import com.amomeal.marketplace.ingredient.exception.IngredientDoesNotExistException;
import com.amomeal.marketplace.ingredient.exception.IngredientSuggestionNotFoundException;
import com.amomeal.marketplace.ingredient.repository.AllergicIngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.ingredient.repository.IngredientSuggestionRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.service.RolePermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Port of ../../backend/dish/services/__init__.py::DishService (everything
 * except the DishLocation block, which is {@link DishLocationService}, and the
 * nutrition math, which is {@link DishNutritionService}) plus the query layer of
 * ../../backend/dish/orm/dish.py::DishORM that those methods call straight
 * through to.
 *
 * <h2>Cross-module seams (PORT-NOTE)</h2>
 * Django's dish queries annotate two things this port cannot query yet, because
 * the owning apps are not ported: {@code sold_count} (order) and
 * {@code review_count}/system average rating (review). They come from
 * {@link DishStatsProvider}. Similarly {@code is_favorite} and the customer's
 * {@code allergy_mode} live on `profile` and come from {@link DishUserContext}.
 * Both default to exactly what Django returns on an empty/absent row, so today's
 * behavior is faithful, and both are single-bean swap-ins later.
 *
 * <p>The allergic-ingredient list itself IS available — {@code ingredient} is
 * ported — so the WARN/HIDE allergy logic is fully implemented, not stubbed.
 */
@Service
@RequiredArgsConstructor
public class DishService {

    private final DishRepository dishRepository;
    private final DishIngredientRepository dishIngredientRepository;
    private final DishAvailabilityRepository availabilityRepository;
    private final DishLocationRepository locationRepository;

    private final IngredientRepository ingredientRepository;
    private final IngredientSuggestionRepository suggestionRepository;
    private final AllergicIngredientRepository allergicIngredientRepository;

    private final AttachmentService attachmentService;
    private final DishNutritionService nutrition;
    private final DishInventoryService inventoryService;
    private final DishSuggestionCandidateFinder candidateFinder;
    private final DishStatsProvider statsProvider;
    private final DishUserContext userContext;

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Django: {@code DishORM.get_dish_by_uid} + {@code raise DishNotFoundException}. */
    @Transactional(readOnly = true)
    public Dish getDishEntity(UUID uid) {
        return dishRepository.findByUidAndDeletedFalse(uid).orElseThrow(DishNotFoundException::new);
    }

    /**
     * Full port of {@code @require_object_permission('dish.change_dish', Dish,
     * owner_field='owner')} — BOTH halves of the decorator, not just ownership:
     * <ol>
     *   <li>{@code request.user.has_perm('dish.change_dish')} — now a real check
     *       against Django's Group→Permission matrix
     *       ({@link RolePermissions}); in practice CHEF or ADMIN (a CUSTOMER holds
     *       only {@code dish.view_dish}), plus any active superuser;</li>
     *   <li>then owner-or-ADMIN on the object itself
     *       ({@code is_owner or request.user.groups.filter(name=ADMIN).exists()}).</li>
     * </ol>
     * The earlier {@code isStaff} stand-in for "admin" is gone — ADMIN is the real
     * Django auth Group membership (PROGRESS.md "Part 1 findings").
     *
     * <p>PORT-NOTE: Django raises {@code PermissionDeniedError} (403
     * PERMISSION_DENIED) from the decorator, where this port raises
     * {@code DishPermissionDeniedException} (403, {@code DISH_PERMISSION_DENIED},
     * carrying the message mix-up catalogued in PROGRESS.md). That difference is
     * pre-existing, asserted by {@code DishControllerTest}, and unchanged here.
     *
     * <p>PORT-NOTE: a few call sites use this for endpoints Django guards with
     * {@code 'dish.view_dish'} instead ({@code get_all_ingredients_of_dish_for_chefs},
     * the two preview endpoints). That is a narrowing with no reachable
     * behavioral difference: every role that can be a dish owner (CHEF) or bypass
     * ownership (ADMIN) holds both permissions, and a CUSTOMER — the only holder
     * of {@code view_dish} without {@code change_dish} — can never own a dish.
     */
    public void assertCanModify(Dish dish, CustomUser user) {
        if (user == null) {
            throw new DishPermissionDeniedException();
        }
        if (!RolePermissions.hasPerm(user, "dish.change_dish")) {
            throw new DishPermissionDeniedException();
        }
        if (user.isAdmin()) {
            return;
        }
        if (dish.getOwner() == null || !dish.getOwner().getId().equals(user.getId())) {
            throw new DishPermissionDeniedException();
        }
    }

    private int inStockToday(UUID dishUid) {
        return availabilityRepository.findTodayAvailability(List.of(dishUid), LocalDate.now()).stream()
                .findFirst()
                .map(DishAvailability::getAvailableQuantity)
                .orElse(0);
    }

    private Map<UUID, Integer> inStockToday(List<UUID> dishUids) {
        if (dishUids.isEmpty()) {
            return Map.of();
        }
        return availabilityRepository.findTodayAvailability(dishUids, LocalDate.now()).stream()
                .collect(Collectors.toMap(a -> a.getDish().getUid(), DishAvailability::getAvailableQuantity,
                        (a, b) -> a));
    }

    private List<UUID> allergicIngredientUids(CustomUser user) {
        if (user == null) {
            return List.of();
        }
        return allergicIngredientRepository.findAllByUserAndDeletedFalse(user).stream()
                .map(AllergicIngredient::getIngredient)
                .map(Ingredient::getUid)
                .toList();
    }

    // =====================================================================
    // Dish CRUD
    // =====================================================================

    /** Django: {@code create_dish}. */
    @Transactional
    public DishResponse createDish(CustomUser user, DishCreateRequest payload) {
        Attachment attachment = null;
        if (payload.attachmentUid() != null) {
            attachment = attachmentService.handleAttachment(payload.attachmentUid());
        }

        DishLocation location = null;
        if (payload.locationId() != null) {
            location = locationRepository.findById(payload.locationId())
                    .orElseThrow(DishLocationNotFoundException::new);
        }
        assertLocationIsCountry(location);

        Dish dish = Dish.builder()
                .name(payload.name())
                .category(payload.category())
                .description(payload.description())
                .price(payload.price())
                .status(payload.statusOrDefault())
                .location(location)
                .owner(user)
                .updater(user)
                .attachment(attachment)
                .servingSize(payload.servingSize() == null ? 1 : payload.servingSize())
                .build();

        Dish saved = dishRepository.save(dish);
        return DishResponse.of(saved, false, false, List.of(), 0L, inStockToday(saved.getUid()));
    }

    /** Django: {@code Dish.clean()} — "Dish must belong to a COUNTRY." */
    private void assertLocationIsCountry(DishLocation location) {
        if (location != null && location.getType() != DishLocationType.COUNTRY) {
            // Django raises django.core.exceptions.ValidationError here, which the ninja
            // plumbing turns into a generic 500 — same as this (see DishLocationService).
            throw new IllegalArgumentException("Dish must belong to a COUNTRY.");
        }
    }

    /** Django: {@code get_all_dishes} (+ the {@code @paginate} decorator on the endpoint). */
    @Transactional(readOnly = true)
    public PageResponse<DishResponse> getAllDishes(String search, String categories, Long chefIdFilter,
                                                   Long locationId, SortBy sortBy, CustomUser user,
                                                   int page, int pageSize) {
        return getDishesInternal(search, categories, chefIdFilter, locationId, sortBy, user, page, pageSize, null);
    }

    /** Django: {@code get_dishes_by_chef} — the same query, plus {@code owner_id=chef_id}. */
    @Transactional(readOnly = true)
    public PageResponse<DishResponse> getDishesByChef(Long chefId, String search, String categories,
                                                      Long locationId, SortBy sortBy, CustomUser user,
                                                      int page, int pageSize) {
        return getDishesInternal(search, categories, null, locationId, sortBy, user, page, pageSize, chefId);
    }

    private PageResponse<DishResponse> getDishesInternal(String search, String categories, Long chefIdFilter,
                                                         Long locationId, SortBy sortBy, CustomUser user,
                                                         int page, int pageSize, Long ownerRestriction) {
        SortBy effectiveSort = sortBy == null ? SortBy.RATING_DESC : sortBy;

        AllergyMode allergyMode = user == null ? AllergyMode.WARN : userContext.allergyMode(user.getId());
        List<UUID> allergicIds = allergicIngredientUids(user);

        // Post-port: suspended (DISH_LOCK) dishes are hidden from customers; ADMIN and the owning
        // chef (list scoped to their own dishes) still see them.
        boolean seesSuspended = user != null && (user.isAdmin()
                || (chefIdFilter != null && chefIdFilter.equals(user.getId()))
                || (ownerRestriction != null && ownerRestriction.equals(user.getId())));
        Specification<Dish> spec = DishSpecifications.allOf(
                DishSpecifications.notDeleted(),
                seesSuspended ? null : DishSpecifications.notSuspended(),
                DishSpecifications.ownedBy(chefIdFilter),
                DishSpecifications.ownedBy(ownerRestriction),
                DishSpecifications.inLocation(locationId),
                DishSpecifications.nameContains(search),
                DishSpecifications.inCategories(categories),
                // HIDE mode -> exclude dishes containing an allergen entirely.
                (user != null && allergyMode == AllergyMode.HIDE)
                        ? DishSpecifications.withoutAllergens(allergicIds) : null);

        int safePage = Math.max(page, 1);
        int safeSize = Math.max(pageSize, 1);

        List<Dish> content;
        long totalRows;

        if (effectiveSort == SortBy.SOLD_ASC || effectiveSort == SortBy.SOLD_DESC) {
            // PORT-NOTE: Django sorts by the `sold_count` Subquery annotation in SQL.
            // sold_count comes from the `order` app, which is not ported, so it is
            // supplied by DishStatsProvider (an in-memory map) and the sort has to
            // happen in memory too. Done over the whole filtered set, then sliced, so
            // paging stays globally correct rather than per-page correct. Once `order`
            // lands, this branch should move back into SQL.
            List<Dish> all = dishRepository.findAll(spec);
            Map<UUID, Long> sold = statsProvider.soldCountByDish(all.stream().map(Dish::getUid).toList());
            Comparator<Dish> comparator = Comparator.comparingLong(d -> sold.getOrDefault(d.getUid(), 0L));
            if (effectiveSort == SortBy.SOLD_DESC) {
                comparator = comparator.reversed();
            }
            all = all.stream().sorted(comparator).toList();
            totalRows = all.size();
            int from = Math.min((safePage - 1) * safeSize, all.size());
            int to = Math.min(from + safeSize, all.size());
            content = all.subList(from, to);
        } else {
            Pageable pageable = PageRequest.of(safePage - 1, safeSize, resolveSort(effectiveSort));
            Page<Dish> result = dishRepository.findAll(spec, pageable);
            content = result.getContent();
            totalRows = result.getTotalElements();
        }

        List<UUID> uids = content.stream().map(Dish::getUid).toList();
        Map<UUID, Long> soldCounts = statsProvider.soldCountByDish(uids);
        Map<UUID, Integer> inStock = inStockToday(uids);
        Set<UUID> favorites = user == null ? Set.of() : userContext.favoriteDishUids(user.getId());

        List<DishResponse> mapped = content.stream()
                .map(d -> DishResponse.of(d, favorites.contains(d.getUid()), false, List.of(),
                        soldCounts.getOrDefault(d.getUid(), 0L), inStock.getOrDefault(d.getUid(), 0)))
                .toList();

        return PageResponse.of(mapped, safePage, safeSize, totalRows);
    }

    /** Django: the sort cascade at the bottom of {@code DishORM.get_all_dishes}. */
    private Sort resolveSort(SortBy sortBy) {
        return switch (sortBy) {
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "price");
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "price");
            case RATING_ASC -> Sort.by(Sort.Direction.ASC, "avgRating");
            // SOLD_* is handled in memory (see getDishesInternal); RATING_DESC is the default.
            default -> Sort.by(Sort.Direction.DESC, "avgRating");
        };
    }

    /**
     * Django: {@code DishORM.get_top_dishes} — the Bayesian ranking. The formula
     * itself is {@link TopDishRanking#bayesianScore}; the ordering below is
     * Django's {@code order_by("-score", "-sold_count", "-review_count", "name")}.
     */
    @Transactional(readOnly = true)
    public List<TopDishResponse> getTopDishes(int limit) {
        List<Dish> dishes = dishRepository.findAllLiveWithRelations().stream()
                .filter(d -> !d.isSuspended()).toList(); // post-port: DISH_LOCK-ed dishes are not ranked
        List<UUID> uids = dishes.stream().map(Dish::getUid).toList();

        double systemAvgRating = statsProvider.systemAverageRating();
        Map<UUID, Long> soldCounts = statsProvider.soldCountByDish(uids);
        Map<UUID, Long> reviewCounts = statsProvider.reviewCountByDish(uids);
        Map<UUID, Integer> inStock = inStockToday(uids);

        record Scored(Dish dish, long sold, long reviews, double score) {
        }

        List<Scored> scored = new ArrayList<>(dishes.size());
        for (Dish dish : dishes) {
            long reviews = reviewCounts.getOrDefault(dish.getUid(), 0L);
            long sold = soldCounts.getOrDefault(dish.getUid(), 0L);
            scored.add(new Scored(dish, sold, reviews,
                    TopDishRanking.bayesianScore(reviews, dish.getAvgRating(), systemAvgRating)));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Comparator.comparingLong(Scored::sold).reversed())
                        .thenComparing(Comparator.comparingLong(Scored::reviews).reversed())
                        .thenComparing(s -> s.dish().getName()))
                .limit(Math.max(limit, 0))
                .map(s -> TopDishResponse.of(s.dish(), s.sold(), s.reviews(), s.score(),
                        inStock.getOrDefault(s.dish().getUid(), 0)))
                .toList();
    }

    /** Django: {@code get_dish_by_uid} (service level — allergy + favourite decoration). */
    @Transactional(readOnly = true)
    public DishResponse getDishByUid(UUID uid, CustomUser user) {
        Dish dish = getDishEntity(uid);

        long soldCount = statsProvider.soldCountByDish(List.of(uid)).getOrDefault(uid, 0L);
        int inStock = inStockToday(uid);

        // GUEST FLOW — defaults, no profile lookups.
        if (user == null) {
            return DishResponse.of(dish, false, false, List.of(), soldCount, inStock);
        }

        // USER FLOW
        AllergyMode allergyMode = userContext.allergyMode(user.getId());
        List<UUID> allergicIds = allergicIngredientUids(user);

        boolean hasAllergy = false;
        List<String> allergenIngredients = List.of();
        if (!allergicIds.isEmpty()) {
            allergenIngredients = dishIngredientRepository.findAllergenIngredientNames(uid, allergicIds);
            hasAllergy = !allergenIngredients.isEmpty();
        }

        // HIDE mode: pretend the dish does not exist at all.
        if (allergyMode == AllergyMode.HIDE && hasAllergy) {
            throw new DishNotFoundException();
        }

        boolean allergyWarning = hasAllergy && allergyMode == AllergyMode.WARN;
        boolean favorite = userContext.favoriteDishUids(user.getId()).contains(uid);

        return DishResponse.of(dish, favorite, allergyWarning, allergenIngredients, soldCount, inStock);
    }

    /**
     * Django: {@code update_dish}. Note the asymmetry Django deliberately builds
     * in: every other field is applied with {@code exclude_none=True} (null =
     * "leave unchanged"), but {@code location_id} is read with
     * {@code hasattr(payload, 'location_id')}, so an explicitly-null location_id
     * CLEARS the location.
     */
    @Transactional
    public DishResponse updateDish(CustomUser user, UUID uid, DishUpdateRequest payload) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);

        if (payload.attachmentUid() != null) {
            dish.setAttachment(attachmentService.handleAttachment(payload.attachmentUid()));
        }

        if (payload.locationId() != null) {
            DishLocation location = locationRepository.findById(payload.locationId())
                    .orElseThrow(DishLocationNotFoundException::new);
            assertLocationIsCountry(location);
            dish.setLocation(location);
        } else {
            // Django: `else: dish.location = None` — clearing is intentional.
            dish.setLocation(null);
        }

        if (payload.name() != null) {
            dish.setName(payload.name());
        }
        if (payload.category() != null) {
            dish.setCategory(payload.category());
        }
        if (payload.description() != null) {
            dish.setDescription(payload.description());
        }
        if (payload.price() != null) {
            dish.setPrice(payload.price());
        }
        if (payload.status() != null) {
            dish.setStatus(payload.status());
        }
        if (payload.servingSize() != null) {
            dish.setServingSize(payload.servingSize());
        }
        dish.setUpdater(user);

        Dish saved = dishRepository.save(dish);
        return DishResponse.of(saved, false, false, List.of(),
                statsProvider.soldCountByDish(List.of(uid)).getOrDefault(uid, 0L), inStockToday(uid));
    }

    /**
     * Django: {@code soft_delete_dish}. PORT-NOTE — Django's
     * {@code DishORM.soft_delete_dish} has its {@code has_related_objects} guard
     * commented out, so it ALWAYS returns True and {@code DishIsReferenced} is
     * never raised from this path. Preserved: no reference check here.
     */
    @Transactional
    public boolean softDeleteDish(CustomUser user, UUID uid) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);
        dish.setDeleted(true);
        dish.setUpdater(user);
        dishRepository.save(dish);
        return true;
    }

    /**
     * Django: {@code hard_delete_dish} -&gt; {@code DishORM.delete_dish}, which
     * catches IntegrityError and turns it into {@code DishIsReferenced}.
     */
    @Transactional
    public boolean hardDeleteDish(CustomUser user, UUID uid) {
        Dish dish = getDishEntity(uid);
        try {
            dishRepository.delete(dish);
            dishRepository.flush();
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new DishIsReferencedException();
        }
    }

    /** Django: {@code restore_dish} — looks the dish up INCLUDING deleted ones. */
    @Transactional
    public boolean restoreDish(CustomUser user, UUID uid) {
        Dish dish = dishRepository.findByUid(uid).orElseThrow(DishNotFoundException::new);
        assertCanModify(dish, user);
        if (!dish.isDeleted()) {
            throw new DishIsNotDeletedException();
        }
        dish.setDeleted(false);
        dish.setUpdater(user);
        dishRepository.save(dish);
        return true;
    }

    // =====================================================================
    // Availability
    // =====================================================================

    /** Django: {@code get_dish_availabilities}. */
    @Transactional(readOnly = true)
    public DishAvailabilityListResponse getDishAvailabilities(UUID uid) {
        Dish dish = dishRepository.findByUidAndDeletedFalse(uid).orElseThrow(DishNotFoundException::new);
        List<DishAvailabilityItemResponse> items = availabilityRepository.findUpcoming(dish, LocalDate.now()).stream()
                .map(a -> new DishAvailabilityItemResponse(a.getAvailableDate(), a.getAvailableQuantity(), a.getNote()))
                .toList();
        return new DishAvailabilityListResponse(dish.getUid().toString(), dish.getName(), items);
    }

    /** Django: {@code create_dish_availabilities}. */
    @Transactional
    public DishAvailabilityListResponse createDishAvailabilities(CustomUser user, UUID uid,
                                                                 DishAvailabilityRequest payload) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);
        inventoryService.createOrUpdateAvailability(dish, payload.availableDate(),
                payload.availableQuantity() == null ? 0 : payload.availableQuantity(), payload.note());
        return getDishAvailabilities(uid);
    }

    // =====================================================================
    // Dish ingredients — read
    // =====================================================================

    /** Django: {@code get_all_ingredients_of_dish_for_customers} + {@code build_response_customer}. */
    @Transactional(readOnly = true)
    public DishIngredientPublicResponse getIngredientsForCustomers(UUID uid) {
        Dish dish = getDishEntity(uid);
        List<DishIngredient> ingredients = dishIngredientRepository.findAllOfDish(dish);

        if (ingredients.isEmpty()) {
            return new DishIngredientPublicResponse(0.0, null, "Không có dữ liệu nguyên liệu", List.of(), null);
        }

        double dishConf = nutrition.computeDishConfidence(ingredients);

        List<DishIngredientPublicResponse.PublicIngredientItem> items = ingredients.stream()
                .map(di -> {
                    // Django: (di.ingredient.name if di.ingredient and di.ingredient.name
                    //          else di.custom_name) or "Unknown Ingredient"
                    String name = (di.getIngredient() != null && di.getIngredient().getName() != null)
                            ? di.getIngredient().getName()
                            : di.getCustomName();
                    if (name == null || name.isEmpty()) {
                        name = "Unknown Ingredient";
                    }
                    return new DishIngredientPublicResponse.PublicIngredientItem(
                            name,
                            nutrition.smartRound("weight", di.getWeight()),
                            nutrition.smartRound("energy", di.getEnergy()),
                            nutrition.smartRound("protein", di.getProtein()),
                            nutrition.smartRound("lipid", di.getLipid()),
                            nutrition.smartRound("carbohydrate", di.getCarbohydrate()),
                            nutrition.smartRound("fiber", di.getFiber()),
                            nutrition.smartRound("natri", di.getNatri()),
                            nutrition.smartRound("cholesterol", di.getCholesterol()));
                })
                .toList();

        return new DishIngredientPublicResponse(
                dishConf,
                nutrition.buildConfidenceText(dishConf),
                nutrition.generateNote(dishConf),
                items,
                NutritionTotalResponse.from(nutrition.computeTotalNutrition(ingredients)));
    }

    /** Django: {@code get_all_ingredients_of_dish_for_chefs} + {@code build_response_chef}. */
    @Transactional(readOnly = true)
    public DishIngredientPrivateResponse getIngredientsForChefs(UUID uid) {
        Dish dish = getDishEntity(uid);
        List<DishIngredient> ingredients = dishIngredientRepository.findAllOfDishForChef(dish);

        if (ingredients.isEmpty()) {
            return new DishIngredientPrivateResponse(0.0, 0.0, "Không có dữ liệu",
                    "Không có dữ liệu nguyên liệu", List.of(), null);
        }

        // PORT-NOTE: Django rounds the dish confidence here with the DEFAULT
        // (customer) config -> 2 decimals, and build_response_chef then rounds that
        // already-rounded value again with the chef config (4 decimals), which is a
        // no-op. Net effect: confidence_of_dish on the chef endpoint carries only 2
        // decimals. Preserved exactly.
        double dishConf = nutrition.smartRound("confidence", nutrition.computeDishConfidence(ingredients));

        List<DishIngredientPrivateResponse.PrivateIngredientItem> items = ingredients.stream()
                .map(di -> new DishIngredientPrivateResponse.PrivateIngredientItem(
                        di.getUid(),
                        di.getComputedIngredientName(),
                        di.isCustomIngredient(),
                        nutrition.smartRound("weight", orZero(di.getWeight()), "chef"),
                        nutrition.smartRound("energy", orZero(di.getEnergy()), "chef"),
                        nutrition.smartRound("protein", orZero(di.getProtein()), "chef"),
                        nutrition.smartRound("lipid", orZero(di.getLipid()), "chef"),
                        nutrition.smartRound("carbohydrate", orZero(di.getCarbohydrate()), "chef"),
                        nutrition.smartRound("fiber", orZero(di.getFiber()), "chef"),
                        nutrition.smartRound("natri", orZero(di.getNatri()), "chef"),
                        nutrition.smartRound("cholesterol", orZero(di.getCholesterol()), "chef"),
                        nutrition.smartRound("confidence", orZero(di.getConfidence()), "chef"),
                        di.getSource(),
                        di.getApprovalStatus(),
                        di.getComputedIngredientUid()))
                .toList();

        return new DishIngredientPrivateResponse(
                nutrition.smartRound("confidence", dishConf, "chef"),
                DishNutritionService.pyRound(dishConf * 100, 1),
                nutrition.buildConfidenceText(dishConf),
                nutrition.generateNote(dishConf),
                items,
                NutritionValuesResponse.from(nutrition.computeTotalNutritionChef(ingredients)));
    }

    private static Double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /** Django: {@code get_dish_ingredient_by_uid}. */
    @Transactional(readOnly = true)
    public DishIngredientDetailResponse getDishIngredientByUid(UUID uid) {
        DishIngredient di = dishIngredientRepository.findByUidAndDeletedFalse(uid)
                .orElseThrow(DishIngredientNotFoundException::new);
        return DishIngredientDetailResponse.from(di);
    }

    // =====================================================================
    // Dish ingredients — write
    // =====================================================================

    /** Django: {@code preview_ingredient_for_dish}. */
    @Transactional(readOnly = true)
    public DishIngredientPreviewResponse previewIngredientForDish(UUID uid, DishIngredientCreateRequest payload) {
        // PORT-NOTE: Django does NOT load the dish on this endpoint at all (the
        // {uid} path param is only used by the permission decorator), so a preview
        // against a non-existent dish 404s on the permission check, never here.
        Ingredient ingredient = payload.ingredientUid() == null ? null
                : ingredientRepository.findByUidAndDeletedFalse(payload.ingredientUid()).orElse(null);
        if (ingredient == null) {
            throw new IngredientDoesNotExistException();
        }

        DishNutritionService.PipelineResult result = nutrition.processIngredientPipeline(
                payload, ingredient, IngredientImportStatus.APPROVED, IngredientSource.USDA);

        return new DishIngredientPreviewResponse(
                ingredient.getUid(),
                ingredient.getName(),
                payload.weight(),
                NutritionValuesResponse.from(result.values()),
                result.warnings(),
                result.confidence());
    }

    /**
     * Django: {@code add_ingredient_to_dish}.
     *
     * <p>PORT-NOTE: when the ingredient is already on the dish, Django does NOT
     * raise {@code DishIngredientAlreadyExists} (despite the endpoint declaring
     * it) — it silently returns the existing row, with the newly computed
     * nutrition/warnings/confidence in the response but WITHOUT persisting them.
     * Preserved exactly.
     */
    @Transactional
    public DishIngredientSaveResponse addIngredientToDish(CustomUser user, UUID uid,
                                                          DishIngredientCreateRequest payload) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);

        Ingredient ingredient = payload.ingredientUid() == null ? null
                : ingredientRepository.findByUidAndDeletedFalse(payload.ingredientUid()).orElse(null);
        if (ingredient == null) {
            throw new IngredientDoesNotExistException();
        }

        DishNutritionService.PipelineResult result = nutrition.processIngredientPipeline(
                payload, ingredient, IngredientImportStatus.APPROVED, IngredientSource.USDA);

        DishIngredient dishIngredient = dishIngredientRepository
                .findFirstByDishAndIngredientAndDeletedFalse(dish, ingredient)
                .orElse(null);

        if (dishIngredient == null) {
            DishIngredient created = DishIngredient.builder()
                    .dish(dish)
                    .ingredient(ingredient)
                    .customName(null)
                    .source(IngredientSource.USDA)
                    .suggestion(null)
                    .createdBy(user)
                    .updatedBy(user)
                    .approvalStatus(IngredientImportStatus.APPROVED)
                    .weight(payload.weight())
                    .confidence(result.confidence())
                    .build();
            NutrientFields.applyAll(created, result.values());
            dishIngredient = dishIngredientRepository.save(created);
        }

        return new DishIngredientSaveResponse(
                dishIngredient.getApprovalStatus().name(),
                dish.getUid(),
                ingredient.getUid(),
                ingredient.getName(),
                dishIngredient.getCustomName(),
                dishIngredient.getSource(),
                dishIngredient.getSuggestion() == null ? null : dishIngredient.getSuggestion().getUid(),
                dishIngredient.getApprovalStatus().name(),
                dishIngredient.getCreatedBy() == null ? null : dishIngredient.getCreatedBy().getId(),
                dishIngredient.getUpdatedBy() == null ? null : dishIngredient.getUpdatedBy().getId(),
                NutritionValuesResponse.from(result.values()),
                result.warnings(),
                result.confidence());
    }

    /**
     * Django: {@code add_suggested_ingredient_to_dish} — add a still-PENDING
     * custom ingredient the same chef suggested earlier, scaling the nutrition of
     * their most recent DishIngredient for that suggestion by the weight ratio.
     */
    @Transactional
    public DishIngredientBySuggestionResponse addSuggestedIngredientToDish(
            CustomUser user, UUID uid, DishIngredientBySuggestionRequest payload) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);

        IngredientSuggestion suggestion = suggestionRepository.findById(payload.suggestionUid())
                .filter(s -> s.getStatus() == IngredientImportStatus.PENDING && !s.isDeleted())
                .orElseThrow(IngredientSuggestionNotFoundException::new);

        if (dishIngredientRepository.existsByDishAndSuggestionAndDeletedFalse(dish, suggestion)) {
            throw new DishIngredientAlreadyExistsException();
        }

        DishIngredient oldDi = dishIngredientRepository
                .findFirstBySuggestionAndCreatedByOrderByCreatedAtDesc(suggestion, user)
                .orElse(null);

        Map<String, Double> values = new java.util.LinkedHashMap<>();
        for (String field : NutrientFields.DISH_NUTRIENT_FIELDS) {
            Double oldVal = oldDi == null ? null : NutrientFields.get(oldDi, field);
            if (oldVal != null) {
                // Django: (payload.weight / old_di.weight) * old_val — note this divides
                // by old_di.weight with no zero/null guard, exactly as written.
                values.put(field, (payload.weight() / oldDi.getWeight()) * oldVal);
            } else {
                values.put(field, null);
            }
        }

        DishIngredient created = DishIngredient.builder()
                .dish(dish)
                .ingredient(null)
                .weight(payload.weight())
                .customName(suggestion.getSuggestedName())
                .source(IngredientSource.CHEF_SUGGESTION)
                .suggestion(suggestion)
                .createdBy(user)
                .updatedBy(user)
                .approvalStatus(IngredientImportStatus.PENDING)
                .confidence(oldDi == null ? null : oldDi.getConfidence())
                .build();
        NutrientFields.applyAll(created, values);
        DishIngredient saved = dishIngredientRepository.save(created);

        return new DishIngredientBySuggestionResponse(
                "success",
                dish.getUid(),
                saved.getCustomName(),
                saved.getSource(),
                saved.getSuggestion() == null ? null : saved.getSuggestion().getUid(),
                saved.getApprovalStatus(),
                user.getId(),
                user.getId(),
                saved.getConfidence(),
                NutritionValuesResponse.from(values));
    }

    /** Django: {@code update_dish_ingredient}. */
    @Transactional
    public DishIngredientSaveResponse updateDishIngredient(CustomUser user, UUID uid,
                                                           DishIngredientUpdateRequest payload) {
        DishIngredient dishIngredient = dishIngredientRepository.findByUidAndDeletedFalse(uid)
                .orElseThrow(DishIngredientNotFoundException::new);

        Dish dish = dishIngredient.getDish();
        assertCanModify(dish, user);

        Ingredient ingredient = dishIngredient.getIngredient();
        IngredientSource source = payload.source() != null ? payload.source() : dishIngredient.getSource();
        IngredientSuggestion suggestion = dishIngredient.getSuggestion();
        String trimmedCustomName = payload.customName() == null ? "" : payload.customName().strip();
        String customName = !trimmedCustomName.isEmpty() ? trimmedCustomName : dishIngredient.getCustomName();
        IngredientImportStatus approvalStatus = dishIngredient.getApprovalStatus();

        if (payload.ingredientUid() != null) {
            ingredient = ingredientRepository.findById(payload.ingredientUid())
                    .orElseThrow(IngredientDoesNotExistException::new);
            customName = null;
            approvalStatus = IngredientImportStatus.APPROVED;
            source = IngredientSource.USDA;
            suggestion = null;
        } else if (ingredient == null && (customName == null || customName.isEmpty())) {
            // Django raises ninja ValidationError here -> the 401 VALIDATION_ERROR
            // envelope documented in CLAUDE.md §6.
            throw new DishIngredientValidationException(
                    "custom_name", "custom_name la bat buoc khi ingredient_uid = null");
        } else if (ingredient == null && dishIngredient.getApprovalStatus() != IngredientImportStatus.PENDING) {
            approvalStatus = IngredientImportStatus.PENDING;
        } else if (ingredient == null) {
            // PORT-NOTE: this branch is only reachable when the row is ALREADY PENDING
            // and has a custom name — Django then creates a brand-new
            // IngredientSuggestion for it on every update. Preserved; see the
            // IngredientSuggestion creation below.
            suggestion = createBareSuggestion(user, customName);
            approvalStatus = IngredientImportStatus.PENDING;
        }

        DishNutritionService.PipelineResult result =
                nutrition.processIngredientPipeline(payload, ingredient, approvalStatus, source);

        // Django: DishORM.update_dish_ingredient
        dishIngredient.setWeight(payload.weight());
        if (ingredient != null || customName != null) {
            dishIngredient.setIngredient(ingredient);
            dishIngredient.setCustomName(customName);
        }
        dishIngredient.setApprovalStatus(approvalStatus);
        dishIngredient.setSource(source);
        // Django assigns `suggestion` unconditionally (even when null) — preserved.
        dishIngredient.setSuggestion(suggestion);
        dishIngredient.setUpdatedBy(user);
        NutrientFields.applyAll(dishIngredient, result.values());
        DishIngredient updated = dishIngredientRepository.save(dishIngredient);

        return new DishIngredientSaveResponse(
                "success",
                dish.getUid(),
                ingredient == null ? null : ingredient.getUid(),
                ingredient == null ? null : ingredient.getName(),
                customName,
                updated.getSource(),
                updated.getSuggestion() == null ? null : updated.getSuggestion().getUid(),
                approvalStatus.name(),
                updated.getCreatedBy() == null ? null : updated.getCreatedBy().getId(),
                updated.getUpdatedBy() == null ? null : updated.getUpdatedBy().getId(),
                NutritionValuesResponse.from(result.values()),
                result.warnings(),
                result.confidence());
    }

    /** Django: {@code IngredientORM.create_ingredient_suggestion(user, suggested_name, suggested_category=None)}. */
    private IngredientSuggestion createBareSuggestion(CustomUser user, String customName) {
        IngredientSuggestion suggestion = IngredientSuggestion.builder()
                .suggestedName(customName)
                .suggestedCategory(null)
                .createdBy(user)
                .status(IngredientImportStatus.PENDING)
                .build();
        return suggestionRepository.save(suggestion);
    }

    /** Django: {@code soft_delete_dish_ingredient}. */
    @Transactional
    public boolean softDeleteDishIngredient(CustomUser user, UUID uid) {
        DishIngredient dishIngredient = dishIngredientRepository.findByUidAndDeletedFalse(uid)
                .orElseThrow(DishIngredientNotFoundException::new);
        assertCanModify(dishIngredient.getDish(), user);
        dishIngredient.setDeleted(true);
        dishIngredientRepository.save(dishIngredient);
        return true;
    }

    // =====================================================================
    // Ingredient suggestions raised from a dish
    // =====================================================================

    /** Django: {@code preview_suggest_ingredient_for_dish}. */
    @Transactional(readOnly = true)
    public DishIngredientSuggestionPreviewResponse previewSuggestIngredientForDish(
            CustomUser user, UUID uid, DishIngredientSuggestionRequest payload) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);

        if (dishIngredientRepository.existsCustomIngredient(dish, payload.customName(), user)) {
            throw new DishIngredientSuggestionAlreadyExistsException();
        }

        List<CandidateResponse> candidates = candidateFinder.findCandidates(
                payload.customName().strip(), payload.limit() == null ? 10 : payload.limit());

        DishNutritionService.PipelineResult result = nutrition.processIngredientPipeline(
                payload, null, IngredientImportStatus.PENDING, IngredientSource.CHEF_SUGGESTION);

        return new DishIngredientSuggestionPreviewResponse(
                payload.customName().strip(),
                payload.weight(),
                NutritionValuesResponse.from(result.values()),
                candidates,
                result.warnings(),
                result.confidence());
    }

    /**
     * Django: {@code suggest_ingredient_for_dish} — create (or reuse) the
     * IngredientSuggestion, then create the PENDING DishIngredient carrying the
     * chef's own numbers.
     */
    @Transactional
    public com.amomeal.marketplace.ingredient.dto.IngredientSuggestionResponse suggestIngredientForDish(
            CustomUser user, UUID uid, DishIngredientSuggestionRequest payload) {
        Dish dish = getDishEntity(uid);
        assertCanModify(dish, user);

        if (dishIngredientRepository.existsCustomIngredient(dish, payload.customName(), user)) {
            throw new DishIngredientSuggestionAlreadyExistsException();
        }

        Attachment attachment = payload.attachmentUid() == null ? null
                : attachmentService.handleAttachment(payload.attachmentUid());

        // Django: IngredientSuggestionService.create_suggestion — reuse the chef's
        // existing PENDING suggestion with the same name, otherwise create one.
        IngredientSuggestion suggestion = suggestionRepository
                .findFirstBySuggestedNameIgnoreCaseAndStatusAndCreatedBy(
                        payload.customName(), IngredientImportStatus.PENDING, user)
                .orElse(null);
        if (suggestion == null) {
            suggestion = IngredientSuggestion.builder()
                    .suggestedName(payload.customName().strip())
                    .suggestedCategory(payload.category())
                    .createdBy(user)
                    .status(IngredientImportStatus.PENDING)
                    .attachment(attachment)
                    .build();
            suggestion = suggestionRepository.save(suggestion);
        }

        DishNutritionService.PipelineResult result = nutrition.processIngredientPipeline(
                payload, null, suggestion.getStatus(), IngredientSource.CHEF_SUGGESTION);

        // Django: DishORM.create_dish_ingredient_from_suggestion — note it copies the
        // payload fields directly (NOT the pipeline's computed `values`), and that it
        // does not copy cholesterol/retinol/caroten/vitamin_b_1 (absent from the schema).
        DishIngredient dishIngredient = DishIngredient.builder()
                .dish(dish)
                .customName(payload.customName())
                .source(IngredientSource.CHEF_SUGGESTION)
                .approvalStatus(IngredientImportStatus.PENDING)
                .suggestion(suggestion)
                .createdBy(user)
                .updatedBy(user)
                .weight(payload.weight())
                .energy(payload.energy())
                .protein(payload.protein())
                .lipid(payload.lipid())
                .carbohydrate(payload.carbohydrate())
                .fiber(payload.fiber())
                .natri(payload.natri())
                .kali(payload.kali())
                .vitaminB2(payload.vitaminB2())
                .vitaminPp(payload.vitaminPp())
                .vitaminC(payload.vitaminC())
                .calcium(payload.calcium())
                .phosphorus(payload.phosphorus())
                .fe(payload.fe())
                .mg(payload.mg())
                .zn(payload.zn())
                .confidence(result.confidence())
                .build();
        dishIngredientRepository.save(dishIngredient);

        return com.amomeal.marketplace.ingredient.dto.IngredientSuggestionResponse.from(suggestion);
    }

    /** Django: {@code get_dish_ingredient_by_suggestion_uid} (used by ingredient moderation). */
    @Transactional(readOnly = true)
    public DishIngredientDetailResponse getDishIngredientBySuggestionUid(UUID suggestionUid) {
        DishIngredient di = dishIngredientRepository.findFirstBySuggestionUidAndDeletedFalse(suggestionUid)
                .orElseThrow(DishIngredientNotFoundException::new);
        return DishIngredientDetailResponse.from(di);
    }
}
