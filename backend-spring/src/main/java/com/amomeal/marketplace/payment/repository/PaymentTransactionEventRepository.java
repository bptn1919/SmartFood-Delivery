package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.PaymentTransactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionEventRepository extends JpaRepository<PaymentTransactionEvent, Long> {

    /**
     * Django {@code get_last_chain_hash}: {@code order_by("-created_at").first()}. PORT-NOTE: id is
     * added as the tie-breaker so "last" is deterministic when two events share a timestamp
     * (Django's {@code verify_event_chain} already orders by {@code ("created_at", "id")}).
     */
    Optional<PaymentTransactionEvent> findFirstByPaymentTransactionIdOrderByCreatedAtDescIdDesc(Long paymentTransactionId);

    /** Django {@code verify_event_chain}: {@code order_by("created_at", "id")}. */
    List<PaymentTransactionEvent> findByPaymentTransactionIdOrderByCreatedAtAscIdAsc(Long paymentTransactionId);

    /** Django {@code payment.events.filter(signature_valid=True).exists()}. */
    boolean existsByPaymentTransactionIdAndSignatureValidTrue(Long paymentTransactionId);

    /** Django {@code payment.events.filter(event_type="RECONCILIATION_CONFIRMED").exists()}. */
    boolean existsByPaymentTransactionIdAndEventType(Long paymentTransactionId, String eventType);
}
