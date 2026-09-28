package com.amomeal.marketplace.menu.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.menu.dto.*;
import com.amomeal.marketplace.menu.entity.Menu;
import com.amomeal.marketplace.menu.entity.MenuDish;
import com.amomeal.marketplace.menu.entity.MenuStatus;
import com.amomeal.marketplace.menu.exception.MenuDishAlreadyExistsException;
import com.amomeal.marketplace.menu.exception.MenuDoesNotExistException;
import com.amomeal.marketplace.menu.exception.MenuIsNotDeletedException;
import com.amomeal.marketplace.menu.exception.PermissionDeniedException;
import com.amomeal.marketplace.menu.repository.MenuDishRepository;
import com.amomeal.marketplace.menu.repository.MenuRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.service.RolePermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Port of ../../backend/menu/services/__init__.py::MenuService plus the query
 * layer of ../../backend/menu/orm/menu.py::MenuORM those methods call straight
 * through to. Reuses the already-ported {@code dish} module's {@link Dish}
 * entity/{@link DishRepository} for the Menu&harr;Dish relationship rather than
 * redeclaring Dish (CLAUDE.md §8 task instructions).
 *
 * <h2>Authorization — how Django's decorators collapse here</h2>
 * Every object-level endpoint in {@code menu/api.py} is wrapped in
 * {@code @require_object_permission(<codename>, Menu, owner_field='chef')},
 * which does two things BEFORE the view (and therefore this service) ever
 * runs: (1) {@code request.user.has_perm(<codename>)} — CHEF and ADMIN hold
 * {@code menu.change_menu}/{@code menu.delete_menu}, CUSTOMER holds neither
 * (only {@code menu.view_menu}); (2) looks the {@code Menu} up FILTERED
 * {@code deleted=False} (Django's {@code _query_object}, default
 * {@code check_deleted=True}) and requires owner-or-ADMIN.
 * <p>
 * The Django service methods THEMSELVES then redundantly re-check ownership
 * with a strict, non-ADMIN-bypassing comparison
 * ({@code if user.id != menu.chef.id: raise PermissionDenied}) — so in
 * practice ADMIN passes the decorator but is then rejected by the service for
 * every endpoint that has this redundant check. {@link #assertOwnerOnly} below
 * collapses BOTH the decorator's has_perm/deleted-filtered-lookup half AND the
 * service's strict re-check into one call, mirroring how
 * {@code DishService.assertCanModify} already collapses dish's equivalent
 * decorator+service pair into one Java method (see PROGRESS.md).
 * <p>
 * <b>{@code add_dish_to_menu} is the one exception</b> — its Django service
 * method takes no {@code user} parameter at all and performs NO ownership
 * re-check, so for that single endpoint the decorator's owner-OR-ADMIN gate is
 * the only one that ever applies, and an ADMIN who is not the menu's chef CAN
 * succeed. {@link #assertOwnerOrAdmin} models that separately. This asymmetry
 * is real, not a porting mistake — see PROGRESS.md's notes on this module.
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;
    private final MenuDishRepository menuDishRepository;
    private final DishRepository dishRepository;

    // =====================================================================
    // Authorization helpers
    // =====================================================================

    /**
     * Collapses the {@code @require_object_permission(codename, Menu, owner_field='chef')}
     * decorator's has_perm + deleted=False-filtered lookup with the Django
     * service method's own strict (no-ADMIN-bypass) ownership re-check. Used
     * by every object-level endpoint EXCEPT {@code add_dish_to_menu} (see class
     * javadoc) and {@code restore_menu} (see {@link #restoreMenu}, which needs
     * the raw lookup exposed for its own PORT-NOTE).
     */
    private Menu assertOwnerOnly(UUID uid, CustomUser user, String permission) {
        Menu menu = lookupForAuthorization(uid, user, permission);
        if (menu.getChef() == null || !menu.getChef().getId().equals(user.getId())) {
            throw new PermissionDeniedException();
        }
        return menu;
    }

    /**
     * Models the decorator's own owner-OR-ADMIN gate in isolation, for
     * {@code add_dish_to_menu} — the one endpoint whose Django service method
     * does not redundantly re-check ownership afterward (see class javadoc).
     */
    private Menu assertOwnerOrAdmin(UUID uid, CustomUser user, String permission) {
        Menu menu = lookupForAuthorization(uid, user, permission);
        boolean isOwner = menu.getChef() != null && menu.getChef().getId().equals(user.getId());
        if (!isOwner && !user.isAdmin()) {
            throw new PermissionDeniedException();
        }
        return menu;
    }

    /** The has_perm check + deleted=False-filtered lookup shared by both gates above. */
    private Menu lookupForAuthorization(UUID uid, CustomUser user, String permission) {
        if (user == null || !RolePermissions.hasPerm(user, permission)) {
            throw new PermissionDeniedException();
        }
        return menuRepository.findByUidAndDeletedFalse(uid).orElseThrow(MenuDoesNotExistException::new);
    }

    // =====================================================================
    // Menu CRUD
    // =====================================================================

    /** Django: {@code create_new_menu}. */
    @Transactional
    public MenuResponse createNewMenu(CustomUser user, MenuRequest payload) {
        Menu menu = Menu.builder()
                .name(payload.name())
                .description(payload.description())
                .status(payload.status())
                .chef(user)
                .updater(user)
                .build();
        return MenuResponse.of(menuRepository.save(menu));
    }

    /**
     * Django: {@code get_all_menus_of_chef} — customer-facing, only ACTIVE +
     * not-deleted menus, no 404 on an empty result (unlike {@link #getAllMyMenus}).
     */
    @Transactional(readOnly = true)
    public List<MenuResponse> getAllMenusOfChef(Long chefId) {
        return menuRepository.findAllByChef_IdAndDeletedFalseAndStatusOrderByName(chefId, MenuStatus.ACTIVE)
                .stream().map(MenuResponse::of).toList();
    }

    /**
     * Django: {@code get_all_my_menus} — chef's own list, any status but not
     * deleted. PORT-NOTE: Django raises {@code MenuDoesNotExist} (404) when the
     * list comes back empty, unlike the customer-facing sibling above — a chef
     * with zero menus gets a 404 from {@code GET /api/menus/mine}, not an empty
     * array. Preserved verbatim, not "fixed" into an empty-list response.
     */
    @Transactional(readOnly = true)
    public List<MenuResponse> getAllMyMenus(Long chefId) {
        List<Menu> menus = menuRepository.findAllByChef_IdAndDeletedFalseOrderByName(chefId);
        if (menus.isEmpty()) {
            throw new MenuDoesNotExistException();
        }
        return menus.stream().map(MenuResponse::of).toList();
    }

    /**
     * Django: {@code get_menu} — customer detail view. PORT-NOTE: the ORM
     * lookup this uses ({@code get_menu_by_uid}) never filters {@code deleted},
     * only {@code status == ACTIVE} is checked — so a soft-deleted menu whose
     * status is still ACTIVE remains visible here (and via
     * {@link #getAllDishesInMenu}) even though it no longer appears in the
     * chef's own {@code /mine} list or the customer's {@code /chef/{id}} list
     * (both of which DO filter {@code deleted=False}). Preserved verbatim.
     */
    @Transactional(readOnly = true)
    public MenuResponse getMenu(UUID uid) {
        return MenuResponse.of(getActiveMenuIgnoringDeleted(uid));
    }

    /** Django: {@code get_all_dishes_in_menu} — customer view, active MenuDish rows only. */
    @Transactional(readOnly = true)
    public List<com.amomeal.marketplace.dish.dto.DishResponse> getAllDishesInMenu(UUID uid) {
        Menu menu = getActiveMenuIgnoringDeleted(uid);
        // PORT-NOTE: Django's ORM.get_all_dishes_in_menu returns raw Dish model
        // instances, serialized through DishResponse's Meta/field declarations
        // with NO per-user decoration (no resolve_is_favorite/resolve_sold_count/
        // resolve_in_stock methods exist on that schema) — so is_favorite,
        // allergy_warning, allergen_ingredients, sold_count and in_stock all come
        // back at the schema's bare declared defaults (false/false/[]/0/0)
        // regardless of the real dish state, while chef name/location/public_url
        // ARE genuinely resolved. Preserved verbatim rather than "fixing" it to
        // call the fuller per-user dish lookup dish/{uid} uses.
        return menuDishRepository.findAllByMenuAndActiveTrueOrderByPosition(menu).stream()
                .map(MenuDish::getDish)
                .filter(java.util.Objects::nonNull)
                .map(dish -> com.amomeal.marketplace.dish.dto.DishResponse.of(dish, false, false, List.of(), 0, 0))
                .toList();
    }

    private Menu getActiveMenuIgnoringDeleted(UUID uid) {
        Menu menu = menuRepository.findByUid(uid).orElse(null);
        if (menu == null || menu.getStatus() != MenuStatus.ACTIVE) {
            throw new MenuDoesNotExistException();
        }
        return menu;
    }

    /** Django: {@code update_menu}. */
    @Transactional
    public MenuResponse updateMenu(CustomUser user, UUID uid, MenuRequest payload) {
        Menu menu = assertOwnerOnly(uid, user, "menu.change_menu");
        menu.setName(payload.name());
        menu.setDescription(payload.description());
        menu.setStatus(payload.status());
        menu.setUpdater(user);
        return MenuResponse.of(menuRepository.save(menu));
    }

    /** Django: {@code soft_delete_menu} — note the permission codename is delete_menu, not change_menu. */
    @Transactional
    public boolean softDeleteMenu(CustomUser user, UUID uid) {
        Menu menu = assertOwnerOnly(uid, user, "menu.delete_menu");
        menu.setDeleted(true);
        menu.setUpdater(user);
        menuRepository.save(menu);
        return true;
    }

    /**
     * Django: {@code hard_delete_menu} — {@code @require_permission('menu.delete_menu')}
     * + {@code @require_group(ADMIN)}, no object/ownership check at all (any
     * ADMIN can hard-delete any menu). The role gate is enforced at the
     * controller ({@code @PreAuthorize("hasRole('ADMIN')")}, matching
     * {@code DishController.hardDeleteDish}'s convention); this method mirrors
     * the service's own unfiltered {@code get_menu_by_uid} lookup (no deleted
     * filter — a menu already soft-deleted can still be hard-deleted).
     */
    @Transactional
    public boolean hardDeleteMenu(UUID uid) {
        Menu menu = menuRepository.findByUid(uid).orElseThrow(MenuDoesNotExistException::new);
        menuRepository.delete(menu);
        return true;
    }

    /**
     * Django: {@code restore_menu}. PORT-NOTE — real, preserved bug: unlike
     * {@code dish}'s {@code restore_dish}, whose decorator explicitly passes
     * {@code check_deleted=False}, this endpoint's decorator
     * ({@code @require_object_permission('menu.change_menu', Menu, owner_field='chef')})
     * keeps the DEFAULT {@code check_deleted=True} — so the authorization
     * lookup itself filters {@code deleted=False} and 404s
     * ({@code MenuDoesNotExist}) for a genuinely soft-deleted menu before the
     * "is it actually deleted" check below is ever reached. For a menu that is
     * NOT deleted, the lookup succeeds and this method's own check then always
     * fires. Net effect: {@code PUT /api/menus/{uid}/restore} can never
     * actually restore anything — the same class of dead-code bug already
     * documented for {@code ingredient}'s {@code restore_ingredient}
     * (PROGRESS.md). Preserved verbatim, not fixed.
     */
    @Transactional
    public boolean restoreMenu(CustomUser user, UUID uid) {
        Menu menu = assertOwnerOnly(uid, user, "menu.change_menu");
        if (!menu.isDeleted()) {
            throw new MenuIsNotDeletedException();
        }
        menu.setDeleted(false);
        menu.setUpdater(user);
        menuRepository.save(menu);
        return true;
    }

    // =====================================================================
    // Menu <-> Dish
    // =====================================================================

    /**
     * Django: {@code add_dish_to_menu} — the one object-level endpoint whose
     * service takes no {@code user} argument at all, so ownership is governed
     * SOLELY by the decorator's owner-OR-ADMIN check (see class javadoc):
     * an ADMIN who is not the menu's chef CAN add a dish here, unlike every
     * other object-level menu endpoint.
     */
    @Transactional
    public MenuDishResponse addDishToMenu(CustomUser user, UUID menuUid, AddDishToMenuRequest payload) {
        Menu menu = assertOwnerOrAdmin(menuUid, user, "menu.change_menu");
        // PORT-NOTE: Django looks the dish up with `Dish.objects.filter(uid=...).first()`
        // — no `deleted=False` filter — so a soft-deleted dish can technically be
        // added to a menu. Preserved verbatim.
        Dish dish = dishRepository.findByUid(payload.dishUid()).orElseThrow(DishNotFoundException::new);
        if (menuDishRepository.existsByMenuAndDish(menu, dish)) {
            throw new MenuDishAlreadyExistsException();
        }
        MenuDish menuDish = MenuDish.builder()
                .menu(menu)
                .dish(dish)
                .position(payload.positionOrDefault())
                .active(payload.activeOrDefault())
                .build();
        return MenuDishResponse.of(menuDishRepository.save(menuDish));
    }

    /**
     * Django: {@code get_all_dishes_in_menu_for_chef}. PORT-NOTE: NO decorator
     * at all beyond {@code @require_group(CHEF)} — ownership is checked purely
     * in the service, strictly (no ADMIN bypass), and there is no
     * {@code deleted=False} filter on the menu lookup at any layer, so a chef
     * can still browse their own soft-deleted menu's dish list here (unlike
     * every {@code @require_object_permission}-guarded endpoint). Soft-deleted
     * DISHES are excluded from the listing itself, matching Django's
     * {@code .exclude(dish__deleted=True)}.
     */
    @Transactional(readOnly = true)
    public List<MenuDishDetailResponse> getAllDishesInMenuForChef(CustomUser user, UUID uid, Boolean active) {
        Menu menu = menuRepository.findByUid(uid).orElseThrow(MenuDoesNotExistException::new);
        if (menu.getChef() == null || !menu.getChef().getId().equals(user.getId())) {
            throw new PermissionDeniedException();
        }
        List<MenuDish> rows = active == null
                ? menuDishRepository.findAllByMenuAndDishDeletedFalseOrderByPosition(menu)
                : menuDishRepository.findAllByMenuAndActiveAndDishDeletedFalseOrderByPosition(menu, active);
        return rows.stream().map(MenuDishDetailResponse::of).toList();
    }

    /** Django: {@code active_menu} (endpoint path is {@code /activate}). */
    @Transactional
    public MenuResponse activeMenu(CustomUser user, UUID uid) {
        Menu menu = assertOwnerOnly(uid, user, "menu.change_menu");
        menu.setStatus(MenuStatus.ACTIVE);
        menu.setUpdater(user);
        return MenuResponse.of(menuRepository.save(menu));
    }

    /** Django: {@code deactivate_menu}. */
    @Transactional
    public MenuResponse deactivateMenu(CustomUser user, UUID uid) {
        Menu menu = assertOwnerOnly(uid, user, "menu.change_menu");
        menu.setStatus(MenuStatus.INACTIVE);
        menu.setUpdater(user);
        return MenuResponse.of(menuRepository.save(menu));
    }

    /**
     * Django: {@code activate_dish_in_menu}. PORT-NOTE: Django's ORM method
     * does {@code MenuDish.objects.get(menu=menu, dish=dish)} uncaught — if the
     * dish was never actually linked to this menu, that raises
     * {@code MenuDish.DoesNotExist} straight through to Django's generic
     * 500 handler (no defined exception code for this case at all). This port
     * preserves that crash by letting {@code Optional.orElseThrow()} raise a
     * plain (unchecked, non-{@code ApiException}) exception, which
     * {@code GlobalExceptionHandler}'s catch-all maps to 500
     * {@code CONTACT_ADMIN_FOR_SUPPORT} — the same net behavior. Dish lookup
     * also has no {@code deleted=False} filter, matching Django.
     */
    @Transactional
    public MenuDishResponse activateDishInMenu(CustomUser user, UUID menuUid, UUID dishUid) {
        Menu menu = assertOwnerOnly(menuUid, user, "menu.change_menu");
        Dish dish = dishRepository.findByUid(dishUid).orElseThrow(DishNotFoundException::new);
        MenuDish menuDish = menuDishRepository.findByMenuAndDish(menu, dish).orElseThrow();
        menuDish.setActive(true);
        return MenuDishResponse.of(menuDishRepository.save(menuDish));
    }

    /** Django: {@code deactivate_dish_in_menu} — see {@link #activateDishInMenu} for the shared PORT-NOTEs. */
    @Transactional
    public MenuDishResponse deactivateDishInMenu(CustomUser user, UUID menuUid, UUID dishUid) {
        Menu menu = assertOwnerOnly(menuUid, user, "menu.change_menu");
        Dish dish = dishRepository.findByUid(dishUid).orElseThrow(DishNotFoundException::new);
        MenuDish menuDish = menuDishRepository.findByMenuAndDish(menu, dish).orElseThrow();
        menuDish.setActive(false);
        return MenuDishResponse.of(menuDishRepository.save(menuDish));
    }
}
