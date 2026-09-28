package com.amomeal.marketplace.voucher.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/voucher/models.py::AppliedVoucher — a plain Django
 * {@code models.Model} (NOT {@code BaseModel}), so it gets Django's default
 * auto-incrementing integer primary key, not a UUID {@code uid}. Records a
 * voucher reservation against a checkout (platform vouchers) or an order
 * (shop vouchers), with a 15-minute TTL ({@code reservation_expires_at}) —
 * see {@code VoucherService}'s class javadoc for the full reservation
 * lifecycle this backs.
 *
 * <p><b>{@code checkoutUid}/{@code orderUid} are plain UUID columns, not real
 * FKs</b> — same precedent as {@code dish}'s {@code StockReservation.orderUid}
 * (see V4__init_dish.sql's comment block): {@code order}/{@code Checkout}
 * aren't ported yet. {@code order}'s future port should add the two real FKs;
 * the exact {@code ALTER TABLE} statements are in this table's Flyway
 * migration comment.
 *
 * <p><b>Partial-unique constraints ported verbatim, including a real,
 * reachable Django bug they create</b> — see {@code V9__init_voucher.sql}'s
 * comment and PROGRESS.md: Django's {@code UniqueConstraint(fields=["order",
 * "voucher_type"], condition=Q(voucher_type=SHOP_VOUCHER))} (and the two
 * {@code checkout}-scoped platform equivalents) apply to ALL rows matching
 * that voucher_type for that order/checkout, regardless of {@code status}.
 * Once any {@code SHOP_VOUCHER} reservation for an order transitions away
 * from reusable (e.g. expires), a second reservation attempt for that same
 * order can never INSERT a new row — the unique index still sees the old
 * (non-RESERVED) row occupying that (order, voucher_type) slot — and crashes
 * with an uncaught {@code DataIntegrityViolationException} (500,
 * matching Django's uncaught {@code IntegrityError}). Not fixed, per
 * CLAUDE.md §0.1.
 */
@Entity
@Table(name = "applied_voucher")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppliedVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_uid", nullable = false)
    private Voucher voucher;

    /** PORT-NOTE: raw UUID, not a real FK yet — see class javadoc. */
    @Column(name = "checkout_uid")
    private UUID checkoutUid;

    /** PORT-NOTE: raw UUID, not a real FK yet — see class javadoc. */
    @Column(name = "order_uid")
    private UUID orderUid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private CustomUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, length = 30)
    private VoucherType voucherType;

    @Builder.Default
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherReservationStatus status = VoucherReservationStatus.RESERVED;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
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
        this.updatedAt = Instant.now();
    }

    /** Django: {@code AppliedVoucher.is_expired()}. */
    public boolean isExpired(Instant now) {
        if (status != VoucherReservationStatus.RESERVED) {
            return false;
        }
        if (reservationExpiresAt == null) {
            return false;
        }
        return now.isAfter(reservationExpiresAt);
    }
}
