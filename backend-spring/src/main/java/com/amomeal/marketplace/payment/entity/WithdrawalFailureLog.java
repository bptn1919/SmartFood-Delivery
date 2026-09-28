package com.amomeal.marketplace.payment.entity;

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

/** Mirrors ../../backend/payment/models.py::WithdrawalFailureLog (table wallet_withdrawal_failures). */
@Entity
@Table(name = "wallet_withdrawal_failures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WithdrawalFailureLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uid;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_transaction_id")
    private Long walletTransactionId;

    @Builder.Default
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false, length = 40)
    private String stage;

    @Builder.Default
    @Column(name = "error_type", nullable = false, length = 120)
    private String errorType = "";

    @Builder.Default
    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

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
