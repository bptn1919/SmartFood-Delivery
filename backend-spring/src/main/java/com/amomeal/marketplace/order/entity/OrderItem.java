package com.amomeal.marketplace.order.entity;

import com.amomeal.marketplace.dish.entity.Dish;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Mirrors ../../backend/order/models.py::OrderItem — a plain Django
 * {@code models.Model} (NOT {@code BaseModel}), so an auto-incrementing
 * {@code id}, not a UUID {@code uid}. Same precedent as {@code menu.MenuDish}
 * and {@code voucher.AppliedVoucher}.
 *
 * <p>That integer {@code id} is the one {@code dish.StockReservation} points at
 * ({@code order_item_id}, a Django {@code OneToOneField(..., CASCADE)}): stock
 * is held per (dish, delivery date), and a single order can span several dishes,
 * so the hold's unit is the item, not the order —
 * see ../../backend/ORDER_FLOW_ONBOARDING.md §Level 9. This port's
 * {@code V10__init_order.sql} adds the real FK that
 * {@code V4__init_dish.sql}'s comment block reserved for it.
 *
 * <p>{@code dish_name}/{@code dish_image_url}/{@code price} are deliberately
 * denormalized copies taken at checkout time — the price the customer agreed to
 * must not move when the chef edits the dish later.
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_uid", nullable = false)
    private Order order;

    /** Django: {@code on_delete=SET_NULL} — a deleted dish must not erase order history. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dish_uid")
    private Dish dish;

    @Column(name = "dish_name", nullable = false, length = 255)
    private String dishName;

    @Column(name = "dish_image_url", length = 500)
    private String dishImageUrl;

    @Builder.Default
    @Column(nullable = false)
    private int quantity = 1;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Django: {@code OrderItem.subtotal()}. */
    public BigDecimal subtotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
