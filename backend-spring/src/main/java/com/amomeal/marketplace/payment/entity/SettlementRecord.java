package com.amomeal.marketplace.payment.entity;

import com.amomeal.marketplace.order.entity.PaymentMethod;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors ../../backend/payment/models.py::SettlementRecord — one row per COMPLETED order:
 * how its gross amount splits into the 10% platform fee and the chef payout. Money does
 * NOT move here; the wallet credit is a separate call ({@code credit_internal_wallet}).
 *
 * <p>{@code status} is a String, not an enum: Django writes {@code "CANCELLED"}
 * ({@code handle_order_cancellation_refund}'s COD branch), a value outside its own
 * {@code SETTLEMENT_STATUS_CHOICES}. See {@link SettlementStatus} for the constants.
 */
@Entity
@Table(name = "settlement_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    @Column(name = "order_uid", nullable = false, unique = true)
    private UUID orderUid;

    @Column(name = "chef_id")
    private Long chefId;

    @Builder.Default
    @Column(name = "gross_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "platform_fee", nullable = false, precision = 15, scale = 2)
    private BigDecimal platformFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "chef_payout_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal chefPayoutAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = SettlementStatus.PENDING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Column(name = "payout_id", length = 100)
    private String payoutId;

    @Column(name = "payout_reference", length = 100)
    private String payoutReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settlement_data", columnDefinition = "jsonb")
    private Map<String, Object> settlementData;

    @Column(name = "error_reason", columnDefinition = "text")
    private String errorReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

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
