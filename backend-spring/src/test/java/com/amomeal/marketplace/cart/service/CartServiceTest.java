package com.amomeal.marketplace.cart.service;

import com.amomeal.marketplace.cart.dto.CartAddRequest;
import com.amomeal.marketplace.cart.dto.CartResponse;
import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.cart.entity.CartItem;
import com.amomeal.marketplace.cart.exception.CartNotFoundException;
import com.amomeal.marketplace.cart.exception.InvalidDeliveryDateException;
import com.amomeal.marketplace.cart.repository.CartItemRepository;
import com.amomeal.marketplace.cart.repository.CartRepository;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests (Mockito) for {@link CartService} — in particular the delivery-date
 * validation, the availability-cap/quantity-quirk logic, and the Django bugs this
 * port deliberately preserves (dead {@code CartNotFoundException} branch, the
 * null-dish crash, the {@code quantity_to_add or 1} falsy-zero quirk, and
 * {@code toggle_select}'s missing ownership check, now FIXED post-port: 403). Full-stack coverage, including real HTTP + real-role auth, lives in
 * {@code com.amomeal.marketplace.cart.web.CartControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private DishRepository dishRepository;
    @Mock private DishAvailabilityRepository dishAvailabilityRepository;

    private CartService service;

    private CustomUser user;
    private CustomUser chef;
    private Dish dish;
    private Cart cart;
    private final LocalDate tomorrow = LocalDate.now().plusDays(1);

    @BeforeEach
    void setUp() {
        service = new CartService(cartRepository, cartItemRepository, dishRepository, dishAvailabilityRepository);

        chef = CustomUser.builder().id(1L).username("chef").email("chef@test.com")
                .firstName("Anh").lastName("Bep").build();
        user = CustomUser.builder().id(2L).username("customer").email("customer@test.com").build();
        dish = Dish.builder().uid(UUID.randomUUID()).name("Phở Bò").price(BigDecimal.valueOf(50000)).owner(chef).build();
        cart = Cart.builder().uid(UUID.randomUUID()).owner(user).build();

        when(cartRepository.findByOwner(user)).thenReturn(Optional.of(cart));
        when(dishRepository.findByUidAndDeletedFalse(dish.getUid())).thenReturn(Optional.of(dish));
    }

    private DishAvailability availability(int qty) {
        return DishAvailability.builder().dish(dish).availableDate(tomorrow).available(true).availableQuantity(qty).build();
    }

    // ===================================================================
    // getOrCreateCart
    // ===================================================================

    @Test
    void getOrCreateCart_existing_returnsItWithoutSaving() {
        Cart result = service.getOrCreateCart(user);
        assertThat(result).isSameAs(cart);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void getOrCreateCart_missing_createsAndSaves() {
        CustomUser newUser = CustomUser.builder().id(99L).build();
        when(cartRepository.findByOwner(newUser)).thenReturn(Optional.empty());
        Cart created = Cart.builder().uid(UUID.randomUUID()).owner(newUser).build();
        when(cartRepository.save(any(Cart.class))).thenReturn(created);

        Cart result = service.getOrCreateCart(newUser);

        assertThat(result).isSameAs(created);
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getOwner()).isSameAs(newUser);
    }

    // ===================================================================
    // getCartByUser — including the dead CartNotFoundException branch
    // ===================================================================

    @Test
    void getCartByUser_buildsResponseFromItems() {
        CartItem item = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).quantity(2).selected(true).build();
        when(cartItemRepository.findDetailedByCart(cart)).thenReturn(List.of(item));

        CartResponse response = service.getCartByUser(user);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).deliveryDate()).isEqualTo(tomorrow);
        assertThat(response.items().get(0).chefs()).hasSize(1);
        assertThat(response.items().get(0).chefs().get(0).chefName()).isEqualTo("Anh Bep");
        assertThat(response.items().get(0).chefs().get(0).items().get(0).subtotal()).isEqualTo(100000.0);
        assertThat(response.totalAmount()).isEqualTo(100000.0);
        assertThat(response.message()).isNull();
    }

    @Test
    void getCartByUser_unselectedItems_dontCountTowardTotal() {
        CartItem item = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).quantity(3).selected(false).build();
        when(cartItemRepository.findDetailedByCart(cart)).thenReturn(List.of(item));

        CartResponse response = service.getCartByUser(user);

        assertThat(response.totalAmount()).isEqualTo(0.0);
    }

    @Test
    void getCartByUser_ownerWithNoName_groupsUnderEmptyChefName() {
        Dish noNameDish = Dish.builder().uid(UUID.randomUUID()).name("Mon La").price(BigDecimal.TEN)
                .owner(CustomUser.builder().id(5L).build()).build();
        CartItem item = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(noNameDish)
                .deliveryDate(tomorrow).quantity(1).build();
        when(cartItemRepository.findDetailedByCart(cart)).thenReturn(List.of(item));

        CartResponse response = service.getCartByUser(user);

        // Django: dish.owner.get_full_name() with no first/last name -> "" (no username
        // fallback, unlike dish's own DishResponse.resolveChefName). Preserved verbatim.
        assertThat(response.items().get(0).chefs().get(0).chefName()).isEmpty();
    }

    @Test
    void getCartByUser_deadBranch_getOrCreateReturningNull_throwsCartNotFoundException() {
        CustomUser ghost = CustomUser.builder().id(123L).build();
        when(cartRepository.findByOwner(ghost)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenReturn(null);

        // PORT-NOTE: this can never actually happen via the real Cart.objects.get_or_create
        // equivalent (getOrCreateCart never returns null in practice) -- see
        // CartNotFoundException's javadoc. This test only proves the guard fires if it ever did.
        assertThatThrownBy(() -> service.getCartByUser(ghost)).isInstanceOf(CartNotFoundException.class);
    }

    // ===================================================================
    // getCartItemCount
    // ===================================================================

    @Test
    void getCartItemCount_sumsQuantities() {
        when(cartItemRepository.sumQuantityByCartOwner(user)).thenReturn(7);
        assertThat(service.getCartItemCount(user)).isEqualTo(7);
    }

    @Test
    void getCartItemCount_nullSum_defaultsToZero() {
        when(cartItemRepository.sumQuantityByCartOwner(user)).thenReturn(null);
        assertThat(service.getCartItemCount(user)).isZero();
    }

    // ===================================================================
    // addItem
    // ===================================================================

    @Test
    void addItem_pastDeliveryDate_throwsInvalidDeliveryDateException() {
        CartAddRequest payload = new CartAddRequest(dish.getUid(), LocalDate.now().minusDays(1), 1);
        assertThatThrownBy(() -> service.addItem(user, payload)).isInstanceOf(InvalidDeliveryDateException.class);
        verify(dishAvailabilityRepository, never()).findByDishAndAvailableDate(any(), any());
    }

    @Test
    void addItem_zeroQuantityToAdd_isTreatedAsOne() {
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.empty());

        CartAddRequest payload = new CartAddRequest(dish.getUid(), tomorrow, 0);
        service.addItem(user, payload);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(1);
    }

    @Test
    void addItem_newItem_cappedAtAvailability_setsMessage() {
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.empty());

        CartAddRequest payload = new CartAddRequest(dish.getUid(), tomorrow, 15);
        CartResponse response = service.addItem(user, payload);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(10);
        assertThat(response.message()).contains("Chỉ còn 10");
    }

    @Test
    void addItem_existingItem_accumulatesQuantity_notCapped() {
        CartItem existing = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).quantity(2).build();
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.of(existing));

        CartAddRequest payload = new CartAddRequest(dish.getUid(), tomorrow, 3);
        CartResponse response = service.addItem(user, payload);

        assertThat(existing.getQuantity()).isEqualTo(5);
        assertThat(response.message()).isNull();
        verify(cartItemRepository).save(existing);
    }

    @Test
    void addItem_existingItem_accumulationCappedAtAvailability() {
        CartItem existing = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).quantity(8).build();
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.of(existing));

        CartAddRequest payload = new CartAddRequest(dish.getUid(), tomorrow, 5);
        CartResponse response = service.addItem(user, payload);

        assertThat(existing.getQuantity()).isEqualTo(10);
        assertThat(response.message()).isNotNull();
    }

    @Test
    void addItem_dishNotAvailableOnDate_throwsGenericRuntimeException() {
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.empty());

        CartAddRequest payload = new CartAddRequest(dish.getUid(), tomorrow, 1);
        assertThatThrownBy(() -> service.addItem(user, payload))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(InvalidDeliveryDateException.class)
                .hasMessageContaining(dish.getName());
    }

    @Test
    void addItem_unavailableAvailabilityRow_isTreatedAsNotAvailable() {
        DishAvailability unavailable = DishAvailability.builder().dish(dish).availableDate(tomorrow)
                .available(false).availableQuantity(10).build();
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(unavailable));

        CartAddRequest payload = new CartAddRequest(dish.getUid(), tomorrow, 1);
        assertThatThrownBy(() -> service.addItem(user, payload)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void addItem_nonexistentDish_crashesWithNullPointerException() {
        UUID ghostUid = UUID.randomUUID();
        when(dishRepository.findByUidAndDeletedFalse(ghostUid)).thenReturn(Optional.empty());
        when(dishAvailabilityRepository.findByDishAndAvailableDate(isNull(), eq(tomorrow))).thenReturn(Optional.empty());

        // PORT-NOTE: Django's cart service never null-checks dish_orm.get_dish_by_uid's
        // result before referencing dish.name -- AttributeError, uncaught, 500. Ported as
        // an equally-uncaught NullPointerException (same 500 CONTACT_ADMIN_FOR_SUPPORT
        // outcome via GlobalExceptionHandler's catch-all).
        CartAddRequest payload = new CartAddRequest(ghostUid, tomorrow, 1);
        assertThatThrownBy(() -> service.addItem(user, payload)).isInstanceOf(NullPointerException.class);
    }

    // ===================================================================
    // toggleSelect
    // ===================================================================

    @Test
    void toggleSelect_fromUnselectedToSelected() {
        UUID itemUid = UUID.randomUUID();
        CartItem item = CartItem.builder().uid(itemUid).cart(cart).dish(dish).deliveryDate(tomorrow).selected(false).build();
        when(cartItemRepository.findById(itemUid)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartOwnerAndSelectedTrue(user)).thenReturn(List.of());

        service.toggleSelect(user, itemUid);

        assertThat(item.isSelected()).isTrue();
        verify(cartItemRepository).save(item);
    }

    @Test
    void toggleSelect_fromSelectedToUnselected_skipsCrossDateCheck() {
        UUID itemUid = UUID.randomUUID();
        CartItem item = CartItem.builder().uid(itemUid).cart(cart).dish(dish).deliveryDate(tomorrow).selected(true).build();
        when(cartItemRepository.findById(itemUid)).thenReturn(Optional.of(item));

        service.toggleSelect(user, itemUid);

        assertThat(item.isSelected()).isFalse();
        verify(cartItemRepository, never()).findByCartOwnerAndSelectedTrue(any());
    }

    @Test
    void toggleSelect_differentDeliveryDateThanAlreadySelected_throwsGenericRuntimeException() {
        UUID itemUid = UUID.randomUUID();
        LocalDate dayAfter = tomorrow.plusDays(1);
        CartItem item = CartItem.builder().uid(itemUid).cart(cart).dish(dish).deliveryDate(dayAfter).selected(false).build();
        CartItem alreadySelected = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).selected(true).build();
        when(cartItemRepository.findById(itemUid)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartOwnerAndSelectedTrue(user)).thenReturn(List.of(alreadySelected));

        assertThatThrownBy(() -> service.toggleSelect(user, itemUid)).isInstanceOf(RuntimeException.class);
        assertThat(item.isSelected()).isFalse();
        verify(cartItemRepository, never()).save(item);
    }

    @Test
    void toggleSelect_sameDeliveryDateAsAlreadySelected_succeeds() {
        UUID itemUid = UUID.randomUUID();
        CartItem item = CartItem.builder().uid(itemUid).cart(cart).dish(dish).deliveryDate(tomorrow).selected(false).build();
        CartItem alreadySelected = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).selected(true).build();
        when(cartItemRepository.findById(itemUid)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartOwnerAndSelectedTrue(user)).thenReturn(List.of(alreadySelected));

        service.toggleSelect(user, itemUid);

        assertThat(item.isSelected()).isTrue();
    }

    @Test
    void toggleSelect_nonexistentUid_crashesWithNullPointerException() {
        UUID ghostUid = UUID.randomUUID();
        when(cartItemRepository.findById(ghostUid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleSelect(user, ghostUid)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toggleSelect_itemInAnotherUsersCart_throwsPermissionDenied_andDoesNotMutate() {
        UUID itemUid = UUID.randomUUID();
        CartItem item = CartItem.builder().uid(itemUid).cart(cart).dish(dish).deliveryDate(tomorrow).selected(false).build();
        when(cartItemRepository.findById(itemUid)).thenReturn(Optional.of(item));
        CustomUser other = CustomUser.builder().id(77L).username("other").email("o@test.com").build();

        assertThatThrownBy(() -> service.toggleSelect(other, itemUid))
                .isInstanceOf(com.amomeal.marketplace.cart.exception.CartPermissionDeniedException.class);
        assertThat(item.isSelected()).isFalse();
        verify(cartItemRepository, never()).save(any());
    }

    // ===================================================================
    // suspended dish (post-port enforcement)
    // ===================================================================

    @Test
    void addItem_suspendedDish_isRejected_andNothingSaved() {
        dish.setSuspended(true);
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));

        assertThatThrownBy(() -> service.addItem(user, new CartAddRequest(dish.getUid(), tomorrow, 1)))
                .isInstanceOf(com.amomeal.marketplace.dish.exception.DishSuspendedException.class);
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void setQuantity_suspendedDish_positiveQuantityRejected_butRemovalStillAllowed() {
        dish.setSuspended(true);
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));

        assertThatThrownBy(() -> service.setQuantity(user, dish.getUid(), tomorrow, 2))
                .isInstanceOf(com.amomeal.marketplace.dish.exception.DishSuspendedException.class);
        service.setQuantity(user, dish.getUid(), tomorrow, 0); // zero = remove: not blocked
    }

    // ===================================================================
    // setQuantity
    // ===================================================================

    @Test
    void setQuantity_zeroOrNegative_deletesExistingItem() {
        CartItem existing = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish).deliveryDate(tomorrow).quantity(3).build();
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.of(existing));

        service.setQuantity(user, dish.getUid(), tomorrow, 0);

        verify(cartItemRepository).delete(existing);
        verify(dishAvailabilityRepository, never()).findByDishAndAvailableDate(any(), any());
    }

    @Test
    void setQuantity_zeroOrNegative_noExistingItem_isNoOp() {
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.empty());

        service.setQuantity(user, dish.getUid(), tomorrow, -5);

        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void setQuantity_cappedAtAvailability_setsMessage() {
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.empty());
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(8)));

        CartResponse response = service.setQuantity(user, dish.getUid(), tomorrow, 100);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(8);
        assertThat(response.message()).contains("Đã giới hạn lại 8");
    }

    @Test
    void setQuantity_updatesExistingItem() {
        CartItem existing = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish).deliveryDate(tomorrow).quantity(3).build();
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.of(existing));
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.of(availability(10)));

        service.setQuantity(user, dish.getUid(), tomorrow, 5);

        assertThat(existing.getQuantity()).isEqualTo(5);
        verify(cartItemRepository).save(existing);
    }

    @Test
    void setQuantity_dishNotAvailable_throwsGenericRuntimeException() {
        when(cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, tomorrow)).thenReturn(Optional.empty());
        when(dishAvailabilityRepository.findByDishAndAvailableDate(dish, tomorrow)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setQuantity(user, dish.getUid(), tomorrow, 5)).isInstanceOf(RuntimeException.class);
    }

    // ===================================================================
    // removeItem
    // ===================================================================

    @Test
    void removeItem_deletesMatchingItem() {
        service.removeItem(user, dish.getUid(), tomorrow);
        verify(cartItemRepository).deleteByCartAndDishAndDeliveryDate(cart, dish, tomorrow);
    }

    @Test
    void removeItem_nonexistentItem_isSilentNoOp() {
        UUID ghostUid = UUID.randomUUID();
        when(dishRepository.findByUidAndDeletedFalse(ghostUid)).thenReturn(Optional.empty());

        service.removeItem(user, ghostUid, tomorrow);

        verify(cartItemRepository, times(1)).deleteByCartAndDishAndDeliveryDate(eq(cart), isNull(), eq(tomorrow));
    }

    // ===================================================================
    // Cross-module seam for the future `order` port
    // ===================================================================

    @Test
    void getSelectedCartItemsByUser_delegatesToRepository() {
        CartItem selected = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish).selected(true).build();
        when(cartItemRepository.findByCartOwnerAndSelectedTrue(user)).thenReturn(List.of(selected));

        assertThat(service.getSelectedCartItemsByUser(user)).containsExactly(selected);
    }

    @Test
    void getDeliveryDatesOfSelectedItems_returnsFirstDate() {
        CartItem selected = CartItem.builder().uid(UUID.randomUUID()).cart(cart).dish(dish)
                .deliveryDate(tomorrow).selected(true).build();
        when(cartItemRepository.findByCartAndSelectedTrue(cart)).thenReturn(List.of(selected));

        assertThat(service.getDeliveryDatesOfSelectedItems(cart)).contains(tomorrow);
    }

    @Test
    void getDeliveryDatesOfSelectedItems_noneSelected_returnsEmpty() {
        when(cartItemRepository.findByCartAndSelectedTrue(cart)).thenReturn(List.of());
        assertThat(service.getDeliveryDatesOfSelectedItems(cart)).isEmpty();
    }

    @Test
    void clearSelectedItems_returnsMessageWithDeletedCount() {
        when(cartItemRepository.deleteByCartOwnerAndSelectedTrue(user)).thenReturn(3L);

        Map<String, String> result = service.clearSelectedItems(user);

        assertThat(result.get("message")).contains("3");
    }
}
