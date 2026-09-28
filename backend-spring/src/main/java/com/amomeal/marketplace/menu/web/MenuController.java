package com.amomeal.marketplace.menu.web;

import com.amomeal.marketplace.dish.dto.DishResponse;
import com.amomeal.marketplace.menu.dto.*;
import com.amomeal.marketplace.menu.service.MenuService;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/menu/api.py::MenuController. Paths/methods
 * cross-checked against FE-admin/src/utils/constants.js
 * ({@code API_ENDPOINTS.MENUS.*}) and FE-admin/src/services/menuService.js.
 *
 * <h2>Role gating</h2>
 * Django gates {@code create_new_menu}/{@code get_all_my_menus}/
 * {@code get_all_dishes_in_menu_for_chef} with {@code @require_group(CHEF)}
 * &rarr; {@code @PreAuthorize("hasRole('CHEF')")} here, and
 * {@code hard_delete_menu} with {@code @require_permission('menu.delete_menu')}
 * + {@code @require_group(ADMIN)} &rarr; {@code @PreAuthorize("hasRole('ADMIN')")}
 * (ADMIN's permission set already includes {@code menu.delete_menu}, so the
 * combined net effect of both Django decorators is exactly "ADMIN only" — same
 * simplification {@code DishController.hardDeleteDish} already uses).
 *
 * <p>Every other object-level endpoint ({@code update}/{@code soft-delete}/
 * {@code restore}/{@code activate}/{@code deactivate}/{@code add-dish}) is
 * gated by {@code @require_object_permission} in Django, which
 * {@link MenuService}'s {@code assertOwnerOnly}/{@code assertOwnerOrAdmin}
 * helpers implement — see that class's javadoc for the full has_perm +
 * ownership breakdown, including the one endpoint
 * ({@code add_dish_to_menu}) where ADMIN can act on a menu it doesn't own.
 */
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** Django: {@code GET /api/menus/mine} (CHEF). */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('CHEF')")
    public List<MenuResponse> getAllMyMenus(@AuthenticationPrincipal CustomUser user) {
        return menuService.getAllMyMenus(user.getId());
    }

    /** Django: {@code GET /api/menus/chef/{chef_id}} — any authenticated user. */
    @GetMapping("/chef/{chefId}")
    public List<MenuResponse> getAllMenusOfChef(@PathVariable Long chefId) {
        return menuService.getAllMenusOfChef(chefId);
    }

    /** Django: {@code GET /api/menus/{uid}} — customer detail view. */
    @GetMapping("/{uid}")
    public MenuResponse getMenu(@PathVariable UUID uid) {
        return menuService.getMenu(uid);
    }

    /** Django: {@code GET /api/menus/{uid}/dishes} — customer view of active dishes. */
    @GetMapping("/{uid}/dishes")
    public List<DishResponse> getAllDishesInMenu(@PathVariable UUID uid) {
        return menuService.getAllDishesInMenu(uid);
    }

    /** Django: {@code GET /api/menus/{uid}/all-dishes} (CHEF, owner-only in the service). */
    @GetMapping("/{uid}/all-dishes")
    @PreAuthorize("hasRole('CHEF')")
    public List<MenuDishDetailResponse> getAllDishesInMenuForChef(
            @AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
            @RequestParam(required = false) Boolean active) {
        return menuService.getAllDishesInMenuForChef(user, uid, active);
    }

    /** Django: {@code PATCH /api/menus/{uid}/activate}. */
    @PatchMapping("/{uid}/activate")
    public MenuResponse activateMenu(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return menuService.activeMenu(user, uid);
    }

    /** Django: {@code PATCH /api/menus/{uid}/deactivate}. */
    @PatchMapping("/{uid}/deactivate")
    public MenuResponse deactivateMenu(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return menuService.deactivateMenu(user, uid);
    }

    /** Django: {@code PATCH /api/menus/{uid}/dishes/{dish_uid}/activate}. */
    @PatchMapping("/{uid}/dishes/{dishUid}/activate")
    public MenuDishResponse activateDishInMenu(@AuthenticationPrincipal CustomUser user,
                                               @PathVariable UUID uid, @PathVariable UUID dishUid) {
        return menuService.activateDishInMenu(user, uid, dishUid);
    }

    /** Django: {@code PATCH /api/menus/{uid}/dishes/{dish_uid}/deactivate}. */
    @PatchMapping("/{uid}/dishes/{dishUid}/deactivate")
    public MenuDishResponse deactivateDishInMenu(@AuthenticationPrincipal CustomUser user,
                                                 @PathVariable UUID uid, @PathVariable UUID dishUid) {
        return menuService.deactivateDishInMenu(user, uid, dishUid);
    }

    /** Django: {@code POST /api/menus} (CHEF). */
    @PostMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public MenuResponse createNewMenu(@AuthenticationPrincipal CustomUser user,
                                      @Valid @RequestBody MenuRequest payload) {
        return menuService.createNewMenu(user, payload);
    }

    /** Django: {@code PUT /api/menus/{uid}} (owner only). */
    @PutMapping("/{uid}")
    public MenuResponse updateMenu(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                   @Valid @RequestBody MenuRequest payload) {
        return menuService.updateMenu(user, uid, payload);
    }

    /** Django: {@code PUT /api/menus/{uid}/deleted} (owner only). */
    @PutMapping("/{uid}/deleted")
    public boolean softDeleteMenu(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return menuService.softDeleteMenu(user, uid);
    }

    /** Django: {@code DELETE /api/menus/{uid}} (ADMIN). */
    @DeleteMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public boolean hardDeleteMenu(@PathVariable UUID uid) {
        return menuService.hardDeleteMenu(uid);
    }

    /** Django: {@code PUT /api/menus/{uid}/restore} — see {@code MenuService.restoreMenu}'s PORT-NOTE. */
    @PutMapping("/{uid}/restore")
    public boolean restoreMenu(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid) {
        return menuService.restoreMenu(user, uid);
    }

    /** Django: {@code POST /api/menus/{uid}/add-dish/} (owner or ADMIN — see MenuService javadoc). */
    @PostMapping({"/{uid}/add-dish", "/{uid}/add-dish/"})
    public MenuDishResponse addDishToMenu(@AuthenticationPrincipal CustomUser user, @PathVariable UUID uid,
                                          @Valid @RequestBody AddDishToMenuRequest payload) {
        return menuService.addDishToMenu(user, uid, payload);
    }
}
