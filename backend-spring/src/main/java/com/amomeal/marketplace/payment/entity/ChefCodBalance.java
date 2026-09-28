package com.amomeal.marketplace.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mirrors ../../backend/payment/models.py::ChefCODBalance. PORT-NOTE (preserved dead write
 * path): the only writer, {@code update_chef_cod_balance}, is called from nowhere in
 * Django, so rows never exist on a live path and {@code settle_cod_balance_for_chef}
 * always answers "COD balance not found for chef".
 */
@Entity
@Table(name = "chef_cod_balance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefCodBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chef_id", nullable = false, unique = true)
    private Long chefId;

    @Builder.Default
    @Column(name = "unsettled_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal unsettledBalance = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "unsettled_orders_count", nullable = false)
    private int unsettledOrdersCount = 0;

    @Builder.Default
    @Column(name = "total_settled", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalSettled = BigDecimal.ZERO;

    @Column(name = "last_settlement_at")
    private Instant lastSettlementAt;

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
        updatedAt = Instant.now();
    }
}
