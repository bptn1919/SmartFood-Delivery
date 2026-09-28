package com.amomeal.marketplace.order.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/models.py::Order (a Django {@code BaseModel} —
 * UUID {@code uid} PK + created_at/updated_at). One Order = one chef's slice of
 * a {@link Checkout}.
 *
 * <p><b>Table name:</b> {@code "order"} (quoted — it is a SQL reserved word).
 * That exact spelling is what {@code V9__init_voucher.sql}'s comment block
 * already documents for the future {@code applied_voucher.order_uid} FK, so it
 * is kept rather than pluralized.
 *
 * <p><b>Money fields.</b> Django keeps the three discount buckets separately
 * ({@code platform_subtotal_discount}, {@code platform_shipping_discount},
 * {@code shop_discount}) plus their sum, and recomputes them from the
 * {@code voucher.AppliedVoucher} ledger rather than from voucher definitions —
 * see {@code OrderService.recalculateOrderTotal}. {@code total_price =
 * sub_total + tax_and_fees + delivery_fee - total_discount}.
 *
 * <p><b>Snapshotted delivery info.</b> {@code delivery_name/phone/address_text/
 * latitude/longitude} are copied off the Checkout's address at
 * {@code place_order} time, so editing or deleting the address later cannot
 * rewrite order history.
 */
@Entity
@Table(name = "`order`")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checkout_uid", nullable = false)
    private Checkout checkout;

    /** Django: {@code on_delete=SET_NULL} — deleting a user must not delete order history. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private CustomUser owner;

    /** Django: {@code on_delete=CASCADE}, nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chef_id")
    private CustomUser chef;

    @Column(name = "delivery_name", length = 255)
    private String deliveryName;

    @Column(name = "delivery_phone", length = 20)
    private String deliveryPhone;

    @Column(name = "delivery_address_text", length = 500)
    private String deliveryAddressText;

    @Column(name = "delivery_latitude")
    private Double deliveryLatitude;

    @Column(name = "delivery_longitude")
    private Double deliveryLongitude;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 20)
    private DeliveryType deliveryType = DeliveryType.THIRD_PARTY;

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
    @Column(name = "platform_subtotal_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformSubtotalDiscount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "platform_shipping_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformShippingDiscount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "shop_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal shopDiscount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_discount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_price", nullable = false, precision = 30, scale = 2)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    /** Django model default is PENDING; {@code create_order_draft} explicitly overrides it to DRAFT. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status = OrderStatus.PENDING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Builder.Default
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

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
