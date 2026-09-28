package com.amomeal.marketplace.dish.web;

import com.amomeal.marketplace.dish.dto.*;
import com.amomeal.marketplace.dish.entity.SortBy;
import com.amomeal.marketplace.dish.service.DishSearchService;
import com.amomeal.marketplace.dish.service.DishService;
import com.amomeal.marketplace.ingredient.dto.IngredientSuggestionResponse;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/dish/api.py::DishController. Paths/methods
 * cross-checked against FE-admin/src/utils/constants.js
 * ({@code API_ENDPOINTS.DISHES.*}, {@code TOP_DISHES},
 * {@code DISH_PREVIEW_INGREDIENT}, {@code DISH_ADD_INGREDIENT},
 * {@code DISH_PREVIEW_SUGGESTION}, {@code DISH_SUGGEST_INGREDIENT},
 * {@code DISH_AVAILABILITY_CREATE}) and FE-admin/src/services/dishService.js.
 *
 * <h2>Role gating</h2>
 * Django gates {@code create_dish}/{@code get_all_my_dishes} with
 * {@code @require_group(CHEF)} and {@code hard_delete_dish} with
 * {@code @require_group(ADMIN)}. Both are now real: roles are Django auth Group
 * memberships, ported in the {@code users} module as {@code CustomUser.roles}
 * and exposed as {@code ROLE_CHEF}/{@code ROLE_ADMIN} authorities (see
 * PROGRESS.md "Part 1 findings"). The earlier {@code isStaff} -&gt;
 * {@code ROLE_STAFF} stand-in, and the "CHEF endpoints open to anyone
 * authenticated" gap, are both gone.
 *
 * <p>Per-object permission ({@code @require_object_permission('dish.change_dish',
 * Dish, owner_field='owner')}) is implemented in
 * {@code DishService.assertCanModify}, and now covers BOTH halves of that
 * decorator — the model-level {@code has_perm} check as well as the ownership
 * check.
 *
 * <p>Endpoints Django marks {@code auth=False} (list, search, top) are
 * annotated here by simply tolerating a null principal; the security filter
 * chain still requires a token for them, exactly as it does for the other
 * modules already ported — revisit centrally if/when public browsing is wired.
 */
@RestController
@RequestMapping("/api/dishes")
@RequiredArgsConstructor
public class DishController {

    private final DishService dishService;
    private final DishSearchService dishSearchService;

    // ===================================================================
    // Dish CRUD
    // ===================================================================

    /** Django: {@code POST /api/dishes/} (CHEF). */
    @PostMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public DishResponse createDish(@AuthenticationPrincipal CustomUser user,
                                   @Valid @RequestBody DishCreateRequest payload) {
        return dishService.createDish(user, payload);
    }

    /** Django: {@code GET /api/dishes/} (paginated, auth=False). */
    @GetMapping({"", "/"})
    public PageResponse<DishResponse> getAllDishes(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "chef_id", required = false) Long chefId,
            @RequestParam(name = "location_id", required = false) Long locationId,
            @RequestParam(name = "sort_by", required = false, defaultValue = "RATING_DESC") SortBy sortBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return dishService.getAllDishes(search, categories, chefId, locationId, sortBy, user, page, pageSize);
    }

    /** Django: {@code GET /api/dishes/mine} (CHEF) — this chef's own dishes. */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('CHEF')")
    public PageResponse<DishResponse> getAllMyDishes(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "location_id", required = false) Long locationId,
            @RequestParam(name = "sort_by", required = false, defaultValue = "RATING_DESC") SortBy sortBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return dishService.getDishesByChef(user.getId(), search, categories, locationId, sortBy, user, page, pageSize);
    }

    /** Django: {@code GET /api/dishes/search} (auth=False). */
    @GetMapping("/search")
    public DishSearchResponse searchDishes(
            @AuthenticationPrincipal CustomUser user,
            @RequestParam String q,
            @RequestParam(required = false) String category,
            @RequestParam(name = "location_id", required = false) Long locationId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "available_today", defaultValue = "false") boolean availableToday,
            @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100)); // Django: Query(50, ge=1, le=100)
        return dishSearchService.search(q, user, category, locationId, status, availableToday, safeLimit);
    }

    /** Django: {@code GET /api/dishes/top} (auth=False) — Bayesian ranking. */
    @GetMapping("/top")
    public List<TopDishResponse> getTopDishes(@RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50)); // Django: Query(10, ge=1, le=50)
        return dishService.getTopDishes(safeLimit);
    }

    /** Django: {@code GET /api/dishes/{uid}}. */
    @GetMapping("/{uid}")
    public DishResponse getDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return dishService.getDishByUid(uid, user);
    }

    /** Django: {@code PATCH /api/dishes/{uid}} (owner or admin). */
    @PatchMapping("/{uid}")
    public DishResponse updateDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                   @Valid @RequestBody DishUpdateRequest payload) {
        return dishService.updateDish(user, uid, payload);
    }

    /** Django: {@code PUT /api/dishes/{uid}/deleted} (soft delete). */
    @PutMapping("/{uid}/deleted")
    public boolean softDeleteDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return dishService.softDeleteDish(user, uid);
    }

    /** Django: {@code DELETE /api/dishes/{uid}} (ADMIN). */
    @DeleteMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public boolean hardDeleteDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return dishService.hardDeleteDish(user, uid);
    }

    /** Django: {@code PUT /api/dishes/{uid}/restored}. */
    @PutMapping("/{uid}/restored")
    public boolean restoreDish(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return dishService.restoreDish(user, uid);
    }

    // ===================================================================
    // Availability
    // ===================================================================

    /** Django: {@code GET /api/dishes/{uid}/availabilities}. */
    @GetMapping("/{uid}/availabilities")
    public DishAvailabilityListResponse getAvailabilities(@PathVariable UUID uid) {
        return dishService.getDishAvailabilities(uid);
    }

    /** Django: {@code POST /api/dishes/{uid}/availabilities} (owner or admin). */
    @PostMapping("/{uid}/availabilities")
    public DishAvailabilityListResponse createAvailabilities(@AuthenticationPrincipal CustomUser user,
                                                             @PathVariable UUID uid,
                                                             @Valid @RequestBody DishAvailabilityRequest payload) {
        return dishService.createDishAvailabilities(user, uid, payload);
    }

    // ===================================================================
    // Dish ingredients
    // ===================================================================

    /** Django: {@code GET /api/dishes/{uid}/ingredients} — customer view. */
    @GetMapping("/{uid}/ingredients")
    public DishIngredientPublicResponse getIngredientsForCustomers(@PathVariable UUID uid) {
        return dishService.getIngredientsForCustomers(uid);
    }

    /** Django: {@code GET /api/dishes/{uid}/ingredients/chef} — chef view (owner or admin). */
    @GetMapping("/{uid}/ingredients/chef")
    public DishIngredientPrivateResponse getIngredientsForChefs(@AuthenticationPrincipal CustomUser user,
                                                                @PathVariable UUID uid) {
        dishService.assertCanModify(dishService.getDishEntity(uid), user);
        return dishService.getIngredientsForChefs(uid);
    }

    /** Django: {@code POST /api/dishes/{uid}/ingredients/preview}. */
    @PostMapping("/{uid}/ingredients/preview")
    public DishIngredientPreviewResponse previewIngredientForDish(@AuthenticationPrincipal CustomUser user,
                                                                  @PathVariable UUID uid,
                                                                  @Valid @RequestBody DishIngredientCreateRequest payload) {
        dishService.assertCanModify(dishService.getDishEntity(uid), user);
        return dishService.previewIngredientForDish(uid, payload);
    }

    /** Django: {@code POST /api/dishes/{uid}/ingredients}. */
    @PostMapping("/{uid}/ingredients")
    public DishIngredientSaveResponse addIngredientToDish(@AuthenticationPrincipal CustomUser user,
                                                          @PathVariable UUID uid,
                                                          @Valid @RequestBody DishIngredientCreateRequest payload) {
        return dishService.addIngredientToDish(user, uid, payload);
    }

    /** Django: {@code POST /api/dishes/{uid}/ingredients/add-suggested}. */
    @PostMapping("/{uid}/ingredients/add-suggested")
    public DishIngredientBySuggestionResponse addSuggestedIngredientToDish(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @Valid @RequestBody DishIngredientBySuggestionRequest payload) {
        return dishService.addSuggestedIngredientToDish(user, uid, payload);
    }

    /** Django: {@code POST /api/dishes/{uid}/ingredients/suggestion/preview}. */
    @PostMapping("/{uid}/ingredients/suggestion/preview")
    public DishIngredientSuggestionPreviewResponse previewSuggestIngredientForDish(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @Valid @RequestBody DishIngredientSuggestionRequest payload) {
        return dishService.previewSuggestIngredientForDish(user, uid, payload);
    }

    /** Django: {@code POST /api/dishes/{uid}/ingredients/suggestion}. */
    @PostMapping("/{uid}/ingredients/suggestion")
    public IngredientSuggestionResponse suggestIngredientForDish(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @Valid @RequestBody DishIngredientSuggestionRequest payload) {
        return dishService.suggestIngredientForDish(user, uid, payload);
    }
}
