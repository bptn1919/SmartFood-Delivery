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
 * Mirrors ../../backend/payment/models.py::InternalWallet — one per user: the cached
 * {@code balance} (spendable), {@code pending_balance} (a withdrawal in flight) and an
 * HMAC {@code signature} over {@code "{user_id}:{balance}:{pending_balance}"}.
 *
 * <p>The signature/integrity logic lives in {@code WalletService}, not here, because it
 * needs the configured secret and the ledger repository.
 *
 * <p><b>Balance scale matters</b> (see {@code PaymentHmac#pyDecimal} and PROGRESS.md):
 * Django hashes {@code str(Decimal)}, and a value read back from the NUMERIC(15,2) column
 * prints with two decimals ("180000.00") while a freshly quantized one does not ("180000").
 * This entity therefore deliberately does NOT normalize scale anywhere.
 */
@Entity
@Table(name = "internal_wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternalWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Builder.Default
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "pending_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, length = 8)
    private String currency = "VND";

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String signature = "";

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
