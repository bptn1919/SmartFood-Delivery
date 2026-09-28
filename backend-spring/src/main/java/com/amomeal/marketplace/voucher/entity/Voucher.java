package com.amomeal.marketplace.voucher.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/voucher/models.py::Voucher (Django {@code BaseModel}: UUID
 * {@code uid} primary key, {@code created_at}/{@code updated_at}). No soft-delete field —
 * Django's {@code VoucherORM.delete_voucher} does a real {@code voucher.delete()}, ported
 * as a hard delete in {@code VoucherService.deleteVoucher}.
 *
 * <p>{@code chef} is nullable: {@code SHOP_VOUCHER}s are chef-owned, {@code PLATFORM_*}
 * vouchers created by {@code admin} (not ported) have no chef.
 */
@Entity
@Table(name = "voucher")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    @Id
    private UUID uid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chef_id")
    private CustomUser chef;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, length = 30)
    private VoucherType voucherType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private VoucherDiscountType discountType = VoucherDiscountType.PERCENTAGE;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 12, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Builder.Default
    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "end_date", nullable = false)
    private Instant endDate;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Builder.Default
    @Column(name = "usage_limit_per_user", nullable = false)
    private int usageLimitPerUser = 1;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

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
        this.updatedAt = Instant.now();
    }

    /** Django: {@code Voucher.is_valid()} — deliberately does NOT check usage_limit (see that
     * method's own docstring: quota is checked separately via the reservation-count queries). */
    public Validity checkValidity(Instant now) {
        if (!isActive) {
            return new Validity(false, "Voucher không còn hoạt động");
        }
        if (now.isBefore(startDate)) {
            return new Validity(false, "Voucher chưa đến ngày hiệu lực");
        }
        if (now.isAfter(endDate)) {
            return new Validity(false, "Voucher đã hết hạn");
        }
        return new Validity(true, "Voucher hợp lệ");
    }

    public record Validity(boolean valid, String message) {
    }

    /**
     * Django: {@code Voucher.calculate_discount}. Not rounded to money precision on purpose —
     * Django's Decimal arithmetic isn't rounded here either (the model field's
     * {@code decimal_places=2} only constrains DB storage, not this in-memory computation).
     * {@code divide} uses 10 fractional digits purely to avoid
     * {@code ArithmeticException} on a non-terminating decimal (e.g. discount_value=33.33...);
     * this is more precision than Python's default Decimal context would keep, but neither
     * side is truncated to cents before comparison/display downstream.
     */
    public BigDecimal calculateDiscount(BigDecimal subTotal) {
        if (subTotal.compareTo(minOrderAmount) < 0) {
            return BigDecimal.ZERO;
        }
        if (discountType == VoucherDiscountType.FIXED_AMOUNT) {
            return discountValue.min(subTotal);
        }
        BigDecimal discount = subTotal.multiply(discountValue).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP);
        if (maxDiscountAmount != null) {
            discount = discount.min(maxDiscountAmount);
        }
        return discount;
    }

    /** Django: {@code Voucher.calculate_shipping_discount}. */
    public BigDecimal calculateShippingDiscount(BigDecimal deliveryFee, BigDecimal checkoutSubtotal) {
        if (checkoutSubtotal.compareTo(minOrderAmount) < 0) {
            return BigDecimal.ZERO;
        }
        if (discountType == VoucherDiscountType.FIXED_AMOUNT) {
            return discountValue.min(deliveryFee);
        }
        BigDecimal discount = deliveryFee.multiply(discountValue).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP);
        if (maxDiscountAmount != null) {
            discount = discount.min(maxDiscountAmount);
        }
        // Django applies this final cap even in the percentage branch (redundant when no
        // max_discount_amount undercuts it, but preserved verbatim).
        return discount.min(deliveryFee);
    }
}
