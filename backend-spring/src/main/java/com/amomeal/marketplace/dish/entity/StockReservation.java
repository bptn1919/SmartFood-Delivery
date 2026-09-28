package com.amomeal.marketplace.dish.entity;

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
 * Mirrors ../../backend/dish/models.py::StockReservation — the durable ledger
 * for temporary stock holds taken during checkout (see
 * {@link com.amomeal.marketplace.dish.service.StockReservationService} for the
 * full rationale, ported from the Django module docstring). Redis only holds a
 * fast in-memory counter derived from
 * {@code DishAvailability - SUM(RESERVED rows here)}; this table is what
 * survives a Redis outage and what makes confirm()/release() atomic.
 *
 * <h2>PORT-NOTE — {@code orderItemId} is a plain column, not an FK (yet)</h2>
 * Django declares {@code order_item = OneToOneField("order.OrderItem",
 * on_delete=CASCADE)}. The `order` module is not ported yet (dish ports first,
 * per CLAUDE.md §7's dependency order), so there is no {@code order_item} table
 * to reference. This port keeps the exact same semantics that the reservation
 * logic depends on — <b>at most one ledger row per order item, ever</b> — via a
 * {@code UNIQUE} constraint on {@code order_item_id}, and leaves the FK itself
 * for the `order` port to add in its own Flyway migration (a one-line
 * {@code ALTER TABLE ... ADD CONSTRAINT ... REFERENCES order_item(id) ON DELETE
 * CASCADE}).
 *
 * <p>{@code orderUid} is likewise denormalized onto this row rather than being
 * reached through {@code reservation.order_item.order_id} as Django does — it is
 * the only thing the expiry sweep needs from the `order` side
 * ({@code release_expired_holds()} returns the set of touched order uids so the
 * caller can cancel them), so denormalizing it keeps the sweep working
 * standalone today and keeps it a single indexed read once `order` exists.
 */
@Entity
@Table(name = "stock_reservation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_reservation_order_item", columnNames = {"order_item_id"})
}, indexes = {
        @Index(name = "idx_stock_reservation_status_expires", columnList = "status, expires_at"),
        @Index(name = "idx_stock_reservation_dish_date_status", columnList = "dish_uid, available_date, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** See class javadoc PORT-NOTE — unique, but not yet a real FK. */
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    /** See class javadoc PORT-NOTE — denormalized {@code order_item.order_id}. */
    @Column(name = "order_uid")
    private UUID orderUid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_uid", nullable = false)
    private Dish dish;

    @Column(name = "available_date", nullable = false)
    private LocalDate availableDate;

    /** Django: PositiveIntegerField. */
    @Column(nullable = false)
    private int quantity;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StockReservationStatus status = StockReservationStatus.RESERVED;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
