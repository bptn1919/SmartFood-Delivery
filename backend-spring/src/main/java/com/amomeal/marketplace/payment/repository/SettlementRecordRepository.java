package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.payment.entity.SettlementRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    /** Django {@code SettlementRecord.objects.filter(order=order).first()}. */
    Optional<SettlementRecord> findFirstByOrderUid(UUID orderUid);

    List<SettlementRecord> findByChefIdAndPaymentMethodAndStatusIn(Long chefId, PaymentMethod paymentMethod,
                                                                    Collection<String> statuses);

    /** Django {@code queryset.update(status=...)} — no auto_now bump, exactly like QuerySet.update. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SettlementRecord s set s.status = :status where s.id in :ids")
    int updateStatus(@Param("ids") Collection<Long> ids, @Param("status") String status);

    /** Django {@code .filter(chef=..., status=...).aggregate(total=Sum('chef_payout_amount'))} (None when empty). */
    @Query("select sum(s.chefPayoutAmount) from SettlementRecord s where s.chefId = :chefId and s.status = :status")
    BigDecimal sumChefPayout(@Param("chefId") Long chefId, @Param("status") String status);
}
