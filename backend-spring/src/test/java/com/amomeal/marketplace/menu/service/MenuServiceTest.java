package com.amomeal.marketplace.menu.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.exception.DishNotFoundException;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.menu.dto.AddDishToMenuRequest;
import com.amomeal.marketplace.menu.dto.MenuRequest;
import com.amomeal.marketplace.menu.dto.MenuResponse;
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
import com.amomeal.marketplace.users.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests (Mockito) for {@link MenuService} — in particular the collapsed
 * decorator+service authorization chains and the Django quirks this port
 * deliberately preserves (see the service's class javadoc). Full-stack
 * coverage, including real role-based rejection over HTTP, lives in
 * {@code com.amomeal.marketplace.menu.web.MenuControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuServiceTest {

    @Mock private MenuRepository menuRepository;
    @Mock private MenuDishRepository menuDishRepository;
    @Mock private DishRepository dishRepository;

    private MenuService service;

    private CustomUser chef;
    private CustomUser otherChef;
    private CustomUser admin;
    private CustomUser customer;
    private Menu menu;

    private static CustomUser withRoles(CustomUser user, UserRole... roles) {
        for (UserRole role : roles) {
            user.addRole(role);
        }
        return user;
    }

    @BeforeEach
    void setUp() {
        service = new MenuService(menuRepository, menuDishRepository, dishRepository);

        chef = withRoles(CustomUser.builder().id(1L).username("chef").build(), UserRole.CHEF);
        otherChef = withRoles(CustomUser.builder().id(2L).username("other-chef").build(), UserRole.CHEF);
        admin = withRoles(CustomUser.builder().id(3L).username("admin").build(), UserRole.ADMIN);
        customer = withRoles(CustomUser.builder().id(4L).username("buyer").build(), UserRole.CUSTOMER);

        menu = Menu.builder().uid(UUID.randomUUID()).name("Combo trưa").status(MenuStatus.ACTIVE)
                .chef(chef).build();
    }

    // =====================================================================
    // create / list
    // =====================================================================

    @Test
    void createNewMenu_setsChefAndUpdaterFromTheCaller() {
        when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MenuRequest req = new MenuRequest("Combo sáng", "mô tả", MenuStatus.DRAFT);

        MenuResponse response = service.createNewMenu(chef, req);

        assertThat(response.name()).isEqualTo("Combo sáng");
        assertThat(response.status()).isEqualTo(MenuStatus.DRAFT);
        assertThat(response.chef()).isEqualTo(chef.getId());
    }

    /**
     * PORT-NOTE: Django's {@code get_all_my_menus} raises {@code MenuDoesNotExist}
     * (404) on an empty list instead of returning an empty array — preserved verbatim.
     */
    @Test
    void getAllMyMenus_empty_throwsMenuDoesNotExist() {
        when(menuRepository.findAllByChef_IdAndDeletedFalseOrderByName(chef.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> service.getAllMyMenus(chef.getId()))
                .isInstanceOf(MenuDoesNotExistException.class);
    }

    @Test
    void getAllMyMenus_nonEmpty_returnsIt() {
        when(menuRepository.findAllByChef_IdAndDeletedFalseOrderByName(chef.getId())).thenReturn(List.of(menu));
        assertThat(service.getAllMyMenus(chef.getId())).hasSize(1);
    }

    /** Unlike {@code get_all_my_menus}, the customer-facing list does NOT 404 on empty. */
    @Test
    void getAllMenusOfChef_empty_returnsEmptyListWithoutThrowing() {
        when(menuRepository.findAllByChef_IdAndDeletedFalseAndStatusOrderByName(chef.getId(), MenuStatus.ACTIVE))
                .thenReturn(List.of());
        assertThat(service.getAllMenusOfChef(chef.getId())).isEmpty();
    }

    // =====================================================================
    // get_menu / get_all_dishes_in_menu — the "deleted is ignored" quirk
    // =====================================================================

    @Test
    void getMenu_missingOrInactiveStatus_throwsMenuDoesNotExist() {
        when(menuRepository.findByUid(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMenu(UUID.randomUUID())).isInstanceOf(MenuDoesNotExistException.class);

        Menu draft = Menu.builder().uid(UUID.randomUUID()).name("x").status(MenuStatus.DRAFT).chef(chef).build();
        when(menuRepository.findByUid(draft.getUid())).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.getMenu(draft.getUid())).isInstanceOf(MenuDoesNotExistException.class);
    }

    /**
     * PORT-NOTE: Django's {@code get_menu_by_uid} never filters {@code deleted} —
     * only {@code status == ACTIVE} gates visibility here, so a soft-deleted menu
     * that is still ACTIVE remains visible through the customer detail endpoint.
     * Preserved verbatim (see MenuService javadoc).
     */
    @Test
    void getMenu_softDeletedButStillActive_isStillVisible() {
        menu.setDeleted(true);
        when(menuRepository.findByUid(menu.getUid())).thenReturn(Optional.of(menu));
        assertThat(service.getMenu(menu.getUid()).uid()).isEqualTo(menu.getUid());
    }

    @Test
    void getAllDishesInMenu_onlyActiveLinks_andDecorationDefaultsAreHardcoded() {
        when(menuRepository.findByUid(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Phở").price(BigDecimal.TEN).build();
        MenuDish link = MenuDish.builder().id(1L).menu(menu).dish(dish).active(true).position(0).build();
        when(menuDishRepository.findAllByMenuAndActiveTrueOrderByPosition(menu)).thenReturn(List.of(link));

        var dishes = service.getAllDishesInMenu(menu.getUid());

        assertThat(dishes).hasSize(1);
        // PORT-NOTE: no per-user decoration on this path — always the bare defaults.
        assertThat(dishes.get(0).favorite()).isFalse();
        assertThat(dishes.get(0).allergyWarning()).isFalse();
        assertThat(dishes.get(0).soldCount()).isZero();
        assertThat(dishes.get(0).inStock()).isZero();
    }

    // =====================================================================
    // update / soft-delete — has_perm + strict ownership (no ADMIN bypass)
    // =====================================================================

    @Test
    void updateMenu_owner_succeeds() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MenuRequest req = new MenuRequest("Đổi tên", null, MenuStatus.ACTIVE);

        MenuResponse response = service.updateMenu(chef, menu.getUid(), req);

        assertThat(response.name()).isEqualTo("Đổi tên");
    }

    @Test
    void updateMenu_customer_isDenied_beforeOwnershipIsEvenChecked() {
        // CUSTOMER holds only menu.view_menu — has_perm fails first, no repository lookup needed.
        assertThatThrownBy(() -> service.updateMenu(customer, menu.getUid(), new MenuRequest("x", null, MenuStatus.ACTIVE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void updateMenu_anotherChef_isDenied() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.updateMenu(otherChef, menu.getUid(), new MenuRequest("x", null, MenuStatus.ACTIVE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * PORT-NOTE (real quirk, see MenuService javadoc): unlike {@code dish}, an
     * ADMIN who is NOT the menu's chef is denied here too — Django's service
     * re-check is strict ownership with no ADMIN bypass.
     */
    @Test
    void updateMenu_adminWhoIsNotTheOwningChef_isDenied() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.updateMenu(admin, menu.getUid(), new MenuRequest("x", null, MenuStatus.ACTIVE)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void updateMenu_softDeletedMenu_isNotFound() {
        // Mirrors the decorator's own deleted=False-filtered pre-check: the mock
        // simply reports no live row for this uid.
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateMenu(chef, menu.getUid(), new MenuRequest("x", null, MenuStatus.ACTIVE)))
                .isInstanceOf(MenuDoesNotExistException.class);
    }

    @Test
    void softDeleteMenu_owner_succeeds_andUsesTheDeletePermissionCodename() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        when(menuRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.softDeleteMenu(chef, menu.getUid())).isTrue();
        assertThat(menu.isDeleted()).isTrue();
    }

    // =====================================================================
    // restore_menu — the dead-code bug
    // =====================================================================

    @Test
    void restoreMenu_aGenuinelyDeletedMenu_404sBeforeTheDeletedCheckIsEverReached() {
        // check_deleted=True at the decorator layer -> the filtered lookup finds nothing.
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.restoreMenu(chef, menu.getUid()))
                .isInstanceOf(MenuDoesNotExistException.class);
    }

    @Test
    void restoreMenu_aLiveMenu_alwaysHitsMenuIsNotDeleted() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.restoreMenu(chef, menu.getUid()))
                .isInstanceOf(MenuIsNotDeletedException.class);
    }

    // =====================================================================
    // hard_delete_menu — no ownership check at all
    // =====================================================================

    @Test
    void hardDeleteMenu_deletesRegardlessOfDeletedFlag_andHasNoOwnershipCheck() {
        when(menuRepository.findByUid(menu.getUid())).thenReturn(Optional.of(menu));
        assertThat(service.hardDeleteMenu(menu.getUid())).isTrue();
    }

    @Test
    void hardDeleteMenu_missing_throwsMenuDoesNotExist() {
        when(menuRepository.findByUid(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.hardDeleteMenu(UUID.randomUUID()))
                .isInstanceOf(MenuDoesNotExistException.class);
    }

    // =====================================================================
    // add_dish_to_menu — the one endpoint where ADMIN (non-owner) can succeed
    // =====================================================================

    @Test
    void addDishToMenu_owner_succeeds() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Gỏi cuốn").price(BigDecimal.ONE).build();
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        when(menuDishRepository.existsByMenuAndDish(menu, dish)).thenReturn(false);
        when(menuDishRepository.save(any())).thenAnswer(inv -> {
            MenuDish md = inv.getArgument(0);
            md.setId(99L);
            return md;
        });

        var response = service.addDishToMenu(chef, menu.getUid(), new AddDishToMenuRequest(dish.getUid(), 2, false));

        assertThat(response.dish()).isEqualTo(dish.getUid());
        assertThat(response.position()).isEqualTo(2);
        assertThat(response.active()).isFalse();
    }

    /**
     * PORT-NOTE (real quirk, see MenuService javadoc): Django's
     * {@code add_dish_to_menu} service method never re-checks ownership (it isn't
     * even passed a {@code user}), so only the decorator's owner-OR-ADMIN gate
     * applies here — unlike every other object-level menu endpoint, an ADMIN who
     * does not own the menu CAN add a dish to it.
     */
    @Test
    void addDishToMenu_adminWhoIsNotTheOwningChef_isAllowed() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Nem nướng").price(BigDecimal.ONE).build();
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        when(menuDishRepository.existsByMenuAndDish(menu, dish)).thenReturn(false);
        when(menuDishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.addDishToMenu(admin, menu.getUid(), new AddDishToMenuRequest(dish.getUid(), 0, true)))
                .doesNotThrowAnyException();
    }

    @Test
    void addDishToMenu_anotherChefWhoIsNeitherOwnerNorAdmin_isDenied() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.addDishToMenu(otherChef, menu.getUid(),
                new AddDishToMenuRequest(UUID.randomUUID(), 0, true)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void addDishToMenu_unknownDish_throwsDishNotFound() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        when(dishRepository.findByUid(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addDishToMenu(chef, menu.getUid(),
                new AddDishToMenuRequest(UUID.randomUUID(), 0, true)))
                .isInstanceOf(DishNotFoundException.class);
    }

    @Test
    void addDishToMenu_alreadyLinked_throwsMenuDishAlreadyExists() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Chả giò").price(BigDecimal.ONE).build();
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        when(menuDishRepository.existsByMenuAndDish(menu, dish)).thenReturn(true);

        assertThatThrownBy(() -> service.addDishToMenu(chef, menu.getUid(),
                new AddDishToMenuRequest(dish.getUid(), 0, true)))
                .isInstanceOf(MenuDishAlreadyExistsException.class);
    }

    // =====================================================================
    // get_all_dishes_in_menu_for_chef — strict ownership, no decorator at all
    // =====================================================================

    @Test
    void getAllDishesInMenuForChef_owner_succeeds() {
        when(menuRepository.findByUid(menu.getUid())).thenReturn(Optional.of(menu));
        when(menuDishRepository.findAllByMenuAndDishDeletedFalseOrderByPosition(menu)).thenReturn(List.of());
        assertThat(service.getAllDishesInMenuForChef(chef, menu.getUid(), null)).isEmpty();
    }

    @Test
    void getAllDishesInMenuForChef_anyoneElse_isDenied_evenAdmin() {
        when(menuRepository.findByUid(menu.getUid())).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.getAllDishesInMenuForChef(admin, menu.getUid(), null))
                .isInstanceOf(PermissionDeniedException.class);
    }

    // =====================================================================
    // activate/deactivate dish in menu — the unhandled-crash quirk
    // =====================================================================

    @Test
    void activateDishInMenu_notLinked_surfacesAsAGenericFailure_notAnApiException() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Bánh xèo").price(BigDecimal.ONE).build();
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        when(menuDishRepository.findByMenuAndDish(menu, dish)).thenReturn(Optional.empty());

        // PORT-NOTE: Django's MenuDish.objects.get(...) is uncaught here -> an
        // unhandled 500 in Django. This port lets Optional.orElseThrow() raise a
        // plain (non-ApiException) NoSuchElementException, which
        // GlobalExceptionHandler's catch-all maps to the same 500 outcome.
        assertThatThrownBy(() -> service.activateDishInMenu(chef, menu.getUid(), dish.getUid()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void activateDishInMenu_linked_flipsActiveTrue() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Bún riêu").price(BigDecimal.ONE).build();
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        MenuDish link = MenuDish.builder().id(5L).menu(menu).dish(dish).active(false).build();
        when(menuDishRepository.findByMenuAndDish(menu, dish)).thenReturn(Optional.of(link));
        when(menuDishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.activateDishInMenu(chef, menu.getUid(), dish.getUid());
        assertThat(response.active()).isTrue();
    }

    @Test
    void deactivateDishInMenu_linked_flipsActiveFalse() {
        when(menuRepository.findByUidAndDeletedFalse(menu.getUid())).thenReturn(Optional.of(menu));
        Dish dish = Dish.builder().uid(UUID.randomUUID()).name("Cà ri gà").price(BigDecimal.ONE).build();
        when(dishRepository.findByUid(dish.getUid())).thenReturn(Optional.of(dish));
        MenuDish link = MenuDish.builder().id(6L).menu(menu).dish(dish).active(true).build();
        when(menuDishRepository.findByMenuAndDish(menu, dish)).thenReturn(Optional.of(link));
        when(menuDishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.deactivateDishInMenu(chef, menu.getUid(), dish.getUid());
        assertThat(response.active()).isFalse();
    }
}
