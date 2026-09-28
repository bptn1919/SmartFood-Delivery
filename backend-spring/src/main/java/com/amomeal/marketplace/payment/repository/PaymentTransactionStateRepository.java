package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.order.entity.PaymentStatus;
import com.amomeal.marketplace.payment.entity.PaymentTransactionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionStateRepository extends JpaRepository<PaymentTransactionState, Long> {

    Optional<PaymentTransactionState> findByPaymentTransactionId(Long paymentTransactionId);

    /**
     * {@code checkout.payment_transaction.state.status} as a scalar query — always read from the
     * database, never from a (possibly stale) entity in the caller's persistence context.
     */
    @Query("""
            select s.status from PaymentTransactionState s, PaymentTransaction p
             where p.id = s.paymentTransactionId and p.checkoutUid = :checkoutUid
            """)
    Optional<PaymentStatus> findStatusByCheckoutUid(@Param("checkoutUid") UUID checkoutUid);
}
