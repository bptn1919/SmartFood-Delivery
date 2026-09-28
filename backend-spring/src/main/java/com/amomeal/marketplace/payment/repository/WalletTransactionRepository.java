package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.WalletTransaction;
import com.amomeal.marketplace.payment.entity.WalletTransactionStatus;
import com.amomeal.marketplace.payment.entity.WalletTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /** Django {@code WalletTransaction.get_last_chain_hash}: {@code order_by("-created_at").first()} (+ id tie-break). */
    Optional<WalletTransaction> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** Django {@code _verify_wallet_tx_chain}: {@code order_by("created_at")} (+ id tie-break). */
    List<WalletTransaction> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);

    /** Django {@code get_internal_wallet_summary}: {@code order_by("-created_at")[:10]}. */
    List<WalletTransaction> findTop10ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** {@code WalletTransaction.objects.filter(user, transaction_type__in, state__status).aggregate(Sum("amount"))}. */
    @Query("""
            select coalesce(sum(t.amount), 0) from WalletTransaction t, WalletTransactionState s
             where s.walletTransactionId = t.id and t.userId = :userId
               and t.transactionType in :types and s.status = :status
            """)
    BigDecimal sumAmount(@Param("userId") Long userId, @Param("types") Collection<WalletTransactionType> types,
                         @Param("status") WalletTransactionStatus status);

    /** Django {@code _get_daily_withdrawal_sum}: SUCCESS payouts whose {@code processed_at} is inside [start, end]. */
    @Query("""
            select coalesce(sum(t.amount), 0) from WalletTransaction t, WalletTransactionState s
             where s.walletTransactionId = t.id and t.userId = :userId
               and t.transactionType = :type and s.status = :status
               and s.processedAt >= :start and s.processedAt <= :end
            """)
    BigDecimal sumProcessedBetween(@Param("userId") Long userId, @Param("type") WalletTransactionType type,
                                   @Param("status") WalletTransactionStatus status,
                                   @Param("start") Instant start, @Param("end") Instant end);

    /**
     * Per-order escrow evidence (payment open question #3): does a {@code type} ledger line
     * (RELEASE to the chef / REFUND to the customer) already exist for this order? Credit lines
     * are append-only and SUCCESS from insert, so existence == the money moved.
     */
    boolean existsByOrderUidAndTransactionType(UUID orderUid, WalletTransactionType type);

    /** The subset of {@code orderUids} that already have a ledger line of one of {@code types}. */
    @Query("""
            select distinct t.orderUid from WalletTransaction t
             where t.orderUid in :orderUids and t.transactionType in :types
            """)
    List<UUID> findOrderUidsHavingType(@Param("orderUids") Collection<UUID> orderUids,
                                       @Param("types") Collection<WalletTransactionType> types);
}
