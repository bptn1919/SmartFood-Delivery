package com.amomeal.marketplace.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ../../backend/payment/models.py::WalletTransaction — one immutable ledger line per
 * wallet movement (HOLD/RELEASE/REFUND/PAYOUT), HMAC-chained per user.
 *
 * <p><b>Two-layer immutability, like Django:</b>
 * <ol>
 *   <li>ORM layer — Django's {@code save()} raises {@code ValueError} when an update touches
 *       an immutable field; here the whole entity is {@link Immutable}, so Hibernate never
 *       even generates an UPDATE for it.</li>
 *   <li>DB layer — the {@code no_wallet_transaction_update/delete} triggers in
 *       V11__init_payment.sql (copied from Django migration 0012) reject any UPDATE/DELETE
 *       from any client.</li>
 * </ol>
 * The mutable part (status/payout id) is {@link WalletTransactionState}.
 */
@Entity
@Immutable
@Table(name = "wallet_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTransaction {

    public static final String GENESIS_HASH = "0".repeat(64);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Django: {@code FK(Order, SET_NULL, null=True)}. */
    @Column(name = "order_uid")
    private UUID orderUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private WalletTransactionType transactionType;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference_id", length = 120)
    private String referenceId;

    @Column(name = "balance_before", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    @Builder.Default
    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash = GENESIS_HASH;

    @Builder.Default
    @Column(name = "chain_hash", nullable = false, length = 64)
    private String chainHash = "";

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
