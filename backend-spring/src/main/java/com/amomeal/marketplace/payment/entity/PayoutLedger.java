package com.amomeal.marketplace.payment.entity;

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
 * Mirrors ../../backend/payment/models.py::PayoutLedger — the double-entry lines of a
 * {@link SettlementRecord} (PLATFORM_REVENUE + CHEF_PAYOUT per completed order;
 * PAYOUT_REVERSAL on a COD cancellation).
 */
@Entity
@Table(name = "payout_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    /** Django: {@code FK(SettlementRecord, CASCADE)}, NOT NULL. */
    @Column(name = "settlement_record_id")
    private Long settlementRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_type", nullable = false, length = 20)
    private LedgerType ledgerType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "order_uid", nullable = false, length = 100)
    private String orderUid;

    @Column(name = "chef_id")
    private Long chefId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (uid == null) {
            uid = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
