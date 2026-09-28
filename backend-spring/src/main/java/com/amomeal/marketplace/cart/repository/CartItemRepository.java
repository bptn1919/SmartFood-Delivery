package com.amomeal.marketplace.cart.repository;

import com.amomeal.marketplace.cart.entity.Cart;
import com.amomeal.marketplace.cart.entity.CartItem;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /** Mirrors {@code CartORM.get_cart_item}. Spring Data translates a null {@code dish}
     * parameter to {@code dish IS NULL} (same as Django's {@code filter(dish=None)}),
     * so this never throws for an unresolved dish — it just finds nothing. */
    Optional<CartItem> findByCartAndDishAndDeliveryDate(Cart cart, Dish dish, LocalDate deliveryDate);

    /** Mirrors {@code cart.cartitem_fk_cart.all().order_by("delivery_date")} in {@code get_cart_detail}. */
    List<CartItem> findByCartOrderByDeliveryDate(Cart cart);

    /** Same as above but eager-fetches dish/owner/attachment to avoid Django's own
     * N+1-avoidance ({@code select_related}/{@code in_bulk}) turning into N+1 here. */
    @Query("SELECT ci FROM CartItem ci JOIN FETCH ci.dish d LEFT JOIN FETCH d.owner LEFT JOIN FETCH d.attachment "
            + "WHERE ci.cart = :cart ORDER BY ci.deliveryDate")
    List<CartItem> findDetailedByCart(@Param("cart") Cart cart);

    /** Mirrors {@code CartORM.get_selected_cart_items_by_user} (order's future checkout seam). */
    List<CartItem> findByCartOwnerAndSelectedTrue(CustomUser owner);

    /** Mirrors the {@code cart.cartitem_fk_cart.filter(is_selected=True)} half of
     * {@code get_delivery_dates_of_selected_items}. */
    List<CartItem> findByCartAndSelectedTrue(Cart cart);

    /** Mirrors {@code CartORM.delete_cart_item} — a null {@code dish} matches nothing,
     * same IS-NULL translation as {@link #findByCartAndDishAndDeliveryDate}. */
    void deleteByCartAndDishAndDeliveryDate(Cart cart, Dish dish, LocalDate deliveryDate);

    /** Mirrors {@code CartORM.clear_selected_items} (order's future checkout seam). */
    long deleteByCartOwnerAndSelectedTrue(CustomUser owner);

    /** Mirrors {@code CartORM.get_cart_item_count}'s {@code Sum("quantity") or 0}. */
    @Query("SELECT COALESCE(SUM(ci.quantity), 0) FROM CartItem ci WHERE ci.cart.owner = :owner")
    Integer sumQuantityByCartOwner(@Param("owner") CustomUser owner);
}
