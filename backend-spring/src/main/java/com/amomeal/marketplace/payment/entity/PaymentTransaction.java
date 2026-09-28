package com.amomeal.marketplace.payment.entity;

import com.amomeal.marketplace.order.entity.PaymentMethod;
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
 * Mirrors ../../backend/payment/models.py::PaymentTransaction — the IMMUTABLE half of
 * one payment attempt for one {@code Checkout} (amount, method, PayOS order code).
 * Everything that changes lives on {@link PaymentTransactionState}; the audit trail on
 * {@link PaymentTransactionEvent}.
 *
 * <p>FKs are mapped as plain id columns (the DB still enforces them — see
 * V11__init_payment.sql). The payment code runs its steps in several independent
 * transactions to mirror Django's autocommit (see {@code PaymentTx}), so it passes
 * ids around rather than managed associations that would go stale/detached between
 * steps.
 */
@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    /** Django: {@code OneToOneField(Checkout, CASCADE, related_name="payment_transaction")}. */
    @Column(name = "checkout_uid", nullable = false, unique = true)
    private UUID checkoutUid;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Column(name = "vnp_txn_ref", length = 100)
    private String vnpTxnRef;

    @Column(name = "vnp_transaction_no", length = 100)
    private String vnpTransactionNo;

    @Column(name = "vnp_bank_code", length = 50)
    private String vnpBankCode;

    @Column(name = "vnp_card_type", length = 50)
    private String vnpCardType;

    @Column(name = "payos_order_code")
    private Long payosOrderCode;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

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
