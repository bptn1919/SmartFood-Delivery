package com.amomeal.marketplace.cart.service;

import com.amomeal.marketplace.cart.dto.CartAddRequest;
import com.amomeal.marketplace.cart.dto.CartItemResponse;
import com.amomeal.marketplace.cart.dto.CartResponse;
import com.amomeal.marketplace.cart.dto.ChefGroupResponse;
import com.amomeal.marketplace.cart.dto.DateGroupResponse;
import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.cart.entity.CartItem;
import com.amomeal.marketplace.cart.exception.CartNotFoundException;
import com.amomeal.marketplace.cart.exception.CartPermissionDeniedException;
import com.amomeal.marketplace.dish.exception.DishSuspendedException;
import com.amomeal.marketplace.cart.exception.InvalidDeliveryDateException;
import com.amomeal.marketplace.cart.repository.CartItemRepository;
import com.amomeal.marketplace.cart.repository.CartRepository;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.entity.DishAvailability;
import com.amomeal.marketplace.dish.repository.DishAvailabilityRepository;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirrors ../../backend/cart/services/__init__.py::CartService + the parts of
 * ../../backend/cart/orm/cart.py::CartORM that are business logic rather than
 * plain persistence (the grouping/response-building in
 * {@code get_cart_detail}). Reuses the already-ported {@code dish.entity.Dish}
 * / {@code DishRepository} / {@code DishAvailabilityRepository} and
 * {@code users.entity.CustomUser} — none redeclared.
 *
 * <h2>No stock reservation here (confirmed, not assumed)</h2>
 * Grepped {@code dish/services/stock_reservation.py}'s callers in Django:
 * only {@code dish}'s own inventory endpoints and (once ported)
 * {@code order}'s checkout/expiry-sweep flow call
 * {@code reserve}/{@code confirm}/{@code release}. {@code cart} never does —
 * {@code add_item}/{@code set_quantity} only read
 * {@code DishAvailability.available_quantity} as a soft, non-atomic cap for
 * UX (silently reducing the requested quantity and returning a Vietnamese
 * {@code message} field instead of erroring). Real inventory deduction is
 * {@code order}'s job at checkout, not ported here.
 *
 * <h2>No voucher seam needed</h2>
 * Neither {@code cart/models.py}, {@code cart/services/__init__.py} nor
 * {@code cart/orm/cart.py} reference {@code voucher} in any way — voucher
 * preview/application is entirely {@code order}'s concern. Nothing to stub.
 *
 * <h2>Cross-module seam left for the future {@code order} port</h2>
 * Django's {@code order/services/__init__.py::OrderService.checkout} calls
 * three cart-owned methods directly: {@code CartORM.get_selected_cart_items_by_user},
 * {@code CartORM.get_delivery_dates_of_selected_items}, and
 * {@code CartService.clear_selected_items} — none of which {@code cart}'s own
 * {@code api.py} exposes over HTTP. Ported here as public methods
 * ({@link #getSelectedCartItemsByUser}, {@link #getDeliveryDatesOfSelectedItems},
 * {@link #clearSelectedItems}) with no controller route, exactly mirroring
 * Django's own shape — {@code order}'s future port calls these concrete
 * methods directly (forward dependency, no interface seam needed, unlike
 * {@code dish}/{@code users}'s backward seams for not-yet-existing consumers).
 *
 * <h2>Django bugs preserved verbatim (all PORT-NOTE'd below), not fixed</h2>
 * <ol>
 *   <li>{@link #getCartByUser} — {@code CartNotFoundException} is genuinely
 *       dead code (see that exception's javadoc): {@code get_or_create}
 *       never returns falsy.</li>
 *   <li>{@link #addItem}/{@link #setQuantity} — Django's {@code dish_orm.get_dish_by_uid}
 *       returns {@code None} for a missing/deleted dish with no null-check
 *       before use; the very next lines crash with Python {@code AttributeError}
 *       when building the "not available" message. Ported as an uncaught
 *       {@code NullPointerException} (same 500 CONTACT_ADMIN_FOR_SUPPORT
 *       outcome via {@code GlobalExceptionHandler}'s catch-all) — see the
 *       inline comments at each call site.</li>
 *   <li>{@code quantity_to_add}/{@code target_quantity} are declared required
 *       {@code int} in Django's ninja schema, yet the service does
 *       {@code quantity_to_add or 1} — so a client sending literally
 *       {@code 0} silently gets 1 item added instead of 0. Preserved in
 *       {@link #addItem}.</li>
 *   <li>{@link #toggleSelect} takes no {@code user} parameter in Django at
 *       all (the controller receives {@code request.user} but never passes
 *       it to the service) — <b>any authenticated user can toggle any other
 *       user's cart item</b> if they know its uid; there is no ownership
 *       check on this one endpoint, unlike {@code set_quantity}/{@code remove_item}
 *       which both resolve the item through the caller's own cart. Ported
 *       faithfully — flagged in PROGRESS.md, not fixed silently.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final DishRepository dishRepository;
    private final DishAvailabilityRepository dishAvailabilityRepository;

    /** Django: {@code CartORM.get_cart_by_user} — {@code Cart.objects.get_or_create(owner=user)}. */
    @Transactional
    public Cart getOrCreateCart(CustomUser user) {
        return cartRepository.findByOwner(user)
                .orElseGet(() -> cartRepository.save(Cart.builder().owner(user).build()));
    }

    /** Django: {@code CartService.get_cart_by_user}. */
    @Transactional
    public CartResponse getCartByUser(CustomUser user) {
        Cart cart = getOrCreateCart(user);
        // PORT-NOTE: dead branch — getOrCreateCart/get_or_create never returns null/None.
        // Preserved + regression-tested (CartServiceTest) to document the Django quirk.
        if (cart == null) {
            throw new CartNotFoundException();
        }
        return buildCartResponse(cart, null);
    }

    /** Django: {@code CartService.get_cart_item_count} / {@code CartORM.get_cart_item_count}. */
    @Transactional(readOnly = true)
    public int getCartItemCount(CustomUser user) {
        Integer total = cartItemRepository.sumQuantityByCartOwner(user);
        return total == null ? 0 : total;
    }

    /** Post-port suspension enforcement: a DISH_LOCK-ed dish can't enter the cart. */
    private static void requireNotSuspended(Dish dish) {
        if (dish != null && dish.isSuspended()) {
            throw new DishSuspendedException();
        }
    }

    /** Django: {@code CartService.add_item}. */
    @Transactional
    public CartResponse addItem(CustomUser user, CartAddRequest payload) {
        Cart cart = getOrCreateCart(user);
        Dish dish = dishRepository.findByUidAndDeletedFalse(payload.dishUid()).orElse(null);
        requireNotSuspended(dish);
        LocalDate deliveryDate = payload.deliveryDate();
        // Django: `quantity_to_add = payload.quantity_to_add or 1` — 0 is falsy, so it
        // silently becomes 1. Preserved verbatim.
        Integer requested = payload.quantityToAdd();
        int quantityToAdd = (requested == null || requested == 0) ? 1 : requested;

        if (deliveryDate.isBefore(LocalDate.now())) {
            throw new InvalidDeliveryDateException();
        }

        DishAvailability availability = dishAvailabilityRepository
                .findByDishAndAvailableDate(dish, deliveryDate)
                .filter(DishAvailability::isAvailable)
                .orElse(null);
        if (availability == null) {
            // PORT-NOTE: dish.getName() below throws NPE when `dish` is null (missing/
            // deleted dish_uid) — Django crashes the same way (AttributeError building the
            // f-string), uncaught, 500 CONTACT_ADMIN_FOR_SUPPORT either way. For a *valid*
            // dish with simply no availability row for this date, this is Django's actual
            // intended (if crude) behavior: a bare `Exception`, not an ApiException.
            throw new RuntimeException("Món " + dish.getName() + " không có sẵn vào ngày " + deliveryDate);
        }
        int availableQty = availability.getAvailableQuantity();

        CartItem item = cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, deliveryDate).orElse(null);
        String message = null;
        if (item != null) {
            int newQuantity = Math.min(item.getQuantity() + quantityToAdd, availableQty);
            if (newQuantity < item.getQuantity() + quantityToAdd) {
                message = "Chỉ còn " + availableQty + " món " + dish.getName() + " cho ngày " + deliveryDate;
            }
            item.setQuantity(newQuantity);
            cartItemRepository.save(item);
        } else {
            int addQty = Math.min(quantityToAdd, availableQty);
            if (addQty < quantityToAdd) {
                message = "Chỉ còn " + availableQty + " món " + dish.getName() + " cho ngày " + deliveryDate;
            }
            cartItemRepository.save(CartItem.builder().cart(cart).dish(dish).deliveryDate(deliveryDate).quantity(addQty).build());
        }
        return buildCartResponse(cart, message);
    }

    /**
     * Django: {@code CartService.toggle_select}. Post-port fix: takes the caller and 403s on
     * an item outside their own cart (Django's version had no ownership check).
     */
    @Transactional
    public CartResponse toggleSelect(CustomUser user, UUID cartItemUid) {
        CartItem cartItem = cartItemRepository.findById(cartItemUid).orElse(null);
        // PORT-NOTE: cartItem.isSelected() below NPEs for a nonexistent uid — Django's
        // get_cart_item_by_uid returns None and the very next line reads
        // `cart_item.is_selected` uncaught (AttributeError), same 500 outcome either way.
        // Post-port fix (2026-09-25): the item must be in the caller's own cart (Django never checked).
        if (cartItem.getCart().getOwner() == null || !Objects.equals(cartItem.getCart().getOwner().getId(), user.getId())) {
            throw new CartPermissionDeniedException();
        }
        if (!cartItem.isSelected()) {
            List<CartItem> selectedItems = cartItemRepository.findByCartOwnerAndSelectedTrue(cartItem.getCart().getOwner());
            if (!selectedItems.isEmpty()) {
                LocalDate firstSelectedDate = selectedItems.get(0).getDeliveryDate();
                if (!cartItem.getDeliveryDate().equals(firstSelectedDate)) {
                    // Django: bare `Exception(...)`, uncaught -> 500 CONTACT_ADMIN_FOR_SUPPORT.
                    throw new RuntimeException(
                            "Không thể chọn món có ngày giao khác nhau. Các món đã chọn có ngày giao: "
                                    + firstSelectedDate + ", món này có ngày giao: " + cartItem.getDeliveryDate());
                }
            }
        }
        cartItem.setSelected(!cartItem.isSelected());
        cartItemRepository.save(cartItem);
        return buildCartResponse(cartItem.getCart(), null);
    }

    /** Django: {@code CartService.set_quantity}. */
    @Transactional
    public CartResponse setQuantity(CustomUser user, UUID dishUid, LocalDate deliveryDate, int targetQuantity) {
        Cart cart = getOrCreateCart(user);
        Dish dish = dishRepository.findByUidAndDeletedFalse(dishUid).orElse(null);
        CartItem item = cartItemRepository.findByCartAndDishAndDeliveryDate(cart, dish, deliveryDate).orElse(null);

        if (targetQuantity <= 0) {
            if (item != null) {
                cartItemRepository.delete(item);
            }
            return buildCartResponse(cart, null);
        }
        requireNotSuspended(dish);

        DishAvailability availability = dishAvailabilityRepository
                .findByDishAndAvailableDate(dish, deliveryDate)
                .filter(DishAvailability::isAvailable)
                .orElse(null);
        if (availability == null) {
            // See addItem's identical PORT-NOTE — same null-dish crash preserved.
            throw new RuntimeException("Món " + dish.getName() + " không có sẵn vào ngày " + deliveryDate);
        }
        int availableQty = availability.getAvailableQuantity();

        String message = null;
        int finalQuantity = targetQuantity;
        if (targetQuantity > availableQty) {
            finalQuantity = availableQty;
            message = "Đã giới hạn lại " + availableQty + " món " + dish.getName() + " cho ngày " + deliveryDate;
        }

        if (item != null) {
            item.setQuantity(finalQuantity);
            cartItemRepository.save(item);
        } else {
            cartItemRepository.save(CartItem.builder().cart(cart).dish(dish).deliveryDate(deliveryDate).quantity(finalQuantity).build());
        }
        return buildCartResponse(cart, message);
    }

    /** Django: {@code CartService.remove_item}. A nonexistent item is a silent no-op. */
    @Transactional
    public CartResponse removeItem(CustomUser user, UUID dishUid, LocalDate deliveryDate) {
        Cart cart = getOrCreateCart(user);
        Dish dish = dishRepository.findByUidAndDeletedFalse(dishUid).orElse(null);
        cartItemRepository.deleteByCartAndDishAndDeliveryDate(cart, dish, deliveryDate);
        return buildCartResponse(cart, null);
    }

    // =========================================================================
    // Cross-module seam for the future `order` port — no controller route,
    // exactly like Django's own CartORM/CartService methods that api.py never
    // exposes. See class javadoc.
    // =========================================================================

    /** Django: {@code CartORM.get_selected_cart_items_by_user}. */
    @Transactional(readOnly = true)
    public List<CartItem> getSelectedCartItemsByUser(CustomUser user) {
        return cartItemRepository.findByCartOwnerAndSelectedTrue(user);
    }

    /** Django: {@code CartORM.get_delivery_dates_of_selected_items} ("only 1 delivery date per checkout"). */
    @Transactional(readOnly = true)
    public Optional<LocalDate> getDeliveryDatesOfSelectedItems(Cart cart) {
        return cartItemRepository.findByCartAndSelectedTrue(cart).stream()
                .map(CartItem::getDeliveryDate)
                .findFirst();
    }

    /** Django: {@code CartService.clear_selected_items} / {@code CartORM.clear_selected_items}. */
    @Transactional
    public Map<String, String> clearSelectedItems(CustomUser user) {
        long deletedCount = cartItemRepository.deleteByCartOwnerAndSelectedTrue(user);
        return Map.of("message", "Đã xóa " + deletedCount + " sản phẩm được chọn trong giỏ hàng.");
    }

    // =========================================================================
    // Response building — mirrors CartORM.get_cart_detail's grouping.
    // =========================================================================

    private CartResponse buildCartResponse(Cart cart, String message) {
        List<CartItem> items = cartItemRepository.findDetailedByCart(cart);

        LinkedHashMap<LocalDate, LinkedHashMap<String, List<CartItemResponse>>> grouped = new LinkedHashMap<>();
        double total = 0;

        for (CartItem item : items) {
            Dish dish = item.getDish();
            String chef = chefName(dish);
            double price = dish.getPrice().doubleValue();
            double subtotal = item.getQuantity() * price;

            CartItemResponse itemResponse = new CartItemResponse(
                    item.getUid(),
                    dish.getUid(),
                    dish.getName(),
                    dish.getAttachment() == null ? null : dish.getAttachment().getPublicUrl(),
                    price,
                    item.getQuantity(),
                    item.getDeliveryDate(),
                    item.isSelected(),
                    subtotal);

            grouped.computeIfAbsent(item.getDeliveryDate(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(chef, c -> new ArrayList<>())
                    .add(itemResponse);

            if (item.isSelected()) {
                total += subtotal;
            }
        }

        List<DateGroupResponse> structuredItems = new ArrayList<>();
        for (Map.Entry<LocalDate, LinkedHashMap<String, List<CartItemResponse>>> dateEntry : grouped.entrySet()) {
            List<ChefGroupResponse> chefs = new ArrayList<>();
            for (Map.Entry<String, List<CartItemResponse>> chefEntry : dateEntry.getValue().entrySet()) {
                chefs.add(new ChefGroupResponse(chefEntry.getKey(), chefEntry.getValue()));
            }
            structuredItems.add(new DateGroupResponse(dateEntry.getKey(), chefs));
        }

        return new CartResponse(structuredItems, total, message);
    }

    /**
     * Django: {@code dish.owner.get_full_name() if dish.owner else "Unknown Chef"} —
     * NOTE this is deliberately NOT the same as {@code dish.dto.DishResponse.resolveChefName}
     * (which falls back to {@code username} when first/last name are blank): cart's own
     * Django code has no such fallback, so a chef with no first/last name set groups
     * under an empty-string chef name here, exactly like Django would.
     */
    private static String chefName(Dish dish) {
        CustomUser owner = dish.getOwner();
        if (owner == null) {
            return "Unknown Chef";
        }
        String first = owner.getFirstName() == null ? "" : owner.getFirstName().trim();
        String last = owner.getLastName() == null ? "" : owner.getLastName().trim();
        return (first + " " + last).trim();
    }
}
