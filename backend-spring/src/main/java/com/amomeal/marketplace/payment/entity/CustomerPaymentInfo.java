package com.amomeal.marketplace.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Mirrors ../../backend/payment/models.py::CustomerPaymentInfo — a customer's bank account
 * for wallet withdrawals. PORT-NOTE (flagged in PROGRESS.md): {@code bank_account_number}
 * is stored in PLAINTEXT, exactly like Django (no encryption anywhere in that codebase).
 */
@Entity
@Table(name = "customer_payment_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerPaymentInfo implements BankInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Builder.Default
    @Column(name = "bank_name", nullable = false, length = 255)
    private String bankName = "";

    @Builder.Default
    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode = "";

    @Builder.Default
    @Column(name = "bank_account_number", nullable = false, length = 50)
    private String bankAccountNumber = "";

    @Builder.Default
    @Column(name = "bank_account_name", nullable = false, length = 255)
    private String bankAccountName = "";

    @Column(name = "bank_branch", length = 255)
    private String bankBranch;

    /** Django default is True (model), though every live write path sets it explicitly. */
    @Builder.Default
    @Column(name = "is_verified", nullable = false)
    private boolean verified = true;

    @Column(name = "verified_at")
    private Instant verifiedAt;

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
