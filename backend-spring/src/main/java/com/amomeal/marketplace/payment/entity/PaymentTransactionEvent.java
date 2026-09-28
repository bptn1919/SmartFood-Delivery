package com.amomeal.marketplace.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Mirrors ../../backend/payment/models.py::PaymentTransactionEvent — the append-only audit
 * log of one payment, HMAC-chained:
 * {@code chain_hash = HMAC(PAYMENT_EVENT_SECRET, f"{payment_id}:{event_type}:{status_from}:{status_to}:{previous_hash}")}
 * (see {@code PaymentHmac#paymentEventChainHash}). {@code @Immutable}: Hibernate never
 * issues an UPDATE for it (Django has no DB trigger on this table, only on
 * {@code wallet_transactions}; nothing in Django updates an event either).
 */
@Entity
@Immutable
@Table(name = "payment_transaction_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransactionEvent {

    public static final String GENESIS_HASH = "0".repeat(64);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_transaction_id", nullable = false)
    private Long paymentTransactionId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "status_from", length = 20)
    private String statusFrom;

    @Column(name = "status_to", length = 20)
    private String statusTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Builder.Default
    @Column(nullable = false, length = 40)
    private String source = "";

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
        createdAt = Instant.now();
    }
}
