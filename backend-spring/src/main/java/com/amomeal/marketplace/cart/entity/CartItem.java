package com.amomeal.marketplace.cart.entity;

import com.amomeal.marketplace.dish.entity.Dish;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mirrors ../../backend/cart/models.py::CartItem (BaseModel + cart/dish FKs,
 * delivery_date, quantity, is_selected, {@code unique_together = ("cart",
 * "dish", "delivery_date")}). Reuses the already-ported {@code dish.entity.Dish}
 * (not redeclared) — cart does NOT reserve stock against
 * {@code dish.service.StockReservationService}; it only reads
 * {@code DishAvailability.available_quantity} as a soft cap for UX (confirmed
 * by grepping stock_reservation.py's callers in Django: only {@code dish}'s
 * own inventory paths and the not-yet-ported {@code order} module call
 * reserve/confirm/release — {@code cart} never does). Real stock deduction
 * happens at checkout/order time, ported later by {@code order}.
 *
 * <p>{@code selected} is named without an {@code is}-prefix on purpose — Spring
 * Data derives query property names from the entity's declared field name, not
 * from a hypothetical {@code isSelected()}/{@code setSelected()} accessor pair
 * (see CLAUDE.md's {@code ingredient} port note: {@code findAllByIsActiveTrue}
 * didn't resolve for the same reason). {@code @JsonProperty("is_selected")} in
 * {@link com.amomeal.marketplace.cart.dto.CartItemResponse} restores the
 * Django/FE-admin wire name.
 */
@Entity
@Table(name = "cart_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_item_cart_dish_date", columnNames = {"cart_uid", "dish_uid", "delivery_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_uid")
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dish_uid")
    private Dish dish;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    /** Django: PositiveIntegerField(default=1). No DB CHECK constraint in Django either. */
    @Builder.Default
    @Column(nullable = false)
    private int quantity = 1;

    /** Django: {@code is_selected} — "tick chọn hay không". */
    @Builder.Default
    @Column(name = "is_selected", nullable = false)
    private boolean selected = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
