package com.amomeal.marketplace.order.entity;

import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/models.py::Checkout (a Django {@code BaseModel} —
 * UUID {@code uid} PK + created_at/updated_at).
 *
 * <p>One Checkout = one "place order" click = one payment session, but possibly
 * several {@link Order}s (one per chef), because a cart can hold dishes from
 * several chefs. See ../../backend/ORDER_FLOW_ONBOARDING.md §Level 1.
 *
 * <p>{@code delivery_address} is {@code on_delete=SET_NULL} in Django (deleting
 * an address must not delete the customer's order history), and the concrete
 * address text/lat/lng are additionally <i>snapshotted</i> onto each
 * {@link Order} at place-order time so the historical record survives the
 * address being edited or removed.
 */
@Entity
@Table(name = "checkout")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Checkout {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Builder.Default
    @Column(name = "sub_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tax_and_fees", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAndFees = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_price", nullable = false, precision = 30, scale = 2)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_discount", nullable = false, precision = 32, scale = 2)
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_address_id")
    private CustomerAddress deliveryAddress;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 16)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "delivery_time", nullable = false)
    private LocalTime deliveryTime;

    @Builder.Default
    @OneToMany(mappedBy = "checkout", fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
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
