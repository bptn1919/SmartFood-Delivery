package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByUid(UUID uid);

    /** Django: {@code checkout.payment_transaction} (OneToOne reverse accessor). */
    Optional<PaymentTransaction> findByCheckoutUid(UUID checkoutUid);

    /** Django: {@code PaymentTransaction.objects.get(payos_order_code=...)} (raises on duplicates too). */
    Optional<PaymentTransaction> findByPayosOrderCode(Long payosOrderCode);

    /**
     * Django: {@code PaymentTransaction.objects.select_for_update().get(checkout=order.checkout)} —
     * the row lock {@code handle_order_cancellation_refund} takes BEFORE its REFUNDED check
     * ("Acquire row-level lock before status check to prevent double-refund race").
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.checkoutUid = :checkoutUid")
    Optional<PaymentTransaction> findForUpdateByCheckoutUid(@Param("checkoutUid") UUID checkoutUid);

    /**
     * Row lock on one payment ({@code SELECT ... FOR UPDATE}) — serializes the PENDING -> HOLDING
     * claim between a webhook and a reconciliation/return-URL sync of the same payment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.id = :id")
    Optional<PaymentTransaction> findForUpdateById(@Param("id") Long id);

    /**
     * Reconciliation candidates: PayOS payments with an order code whose state is {@code status}
     * (PENDING), created in {@code [createdAfter, createdBefore]}, with {@code id > afterId}, in id
     * order (the job pages through them with a cursor so long-unpaid payments cannot starve the rest).
     */
    @Query("""
            select p from PaymentTransaction p, PaymentTransactionState s
             where s.paymentTransactionId = p.id
               and p.paymentMethod = :method and p.payosOrderCode is not null
               and s.status = :status
               and p.createdAt >= :createdAfter and p.createdAt <= :createdBefore
               and p.id > :afterId
             order by p.id asc
            """)
    java.util.List<PaymentTransaction> findReconciliationCandidates(
            @Param("method") com.amomeal.marketplace.order.entity.PaymentMethod method,
            @Param("status") com.amomeal.marketplace.order.entity.PaymentStatus status,
            @Param("createdAfter") java.time.Instant createdAfter,
            @Param("createdBefore") java.time.Instant createdBefore,
            @Param("afterId") long afterId, org.springframework.data.domain.Pageable pageable);
}
