package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.PayoutLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayoutLedgerRepository extends JpaRepository<PayoutLedger, Long> {

    List<PayoutLedger> findBySettlementRecordIdOrderByIdAsc(Long settlementRecordId);

    List<PayoutLedger> findByOrderUidOrderByIdAsc(String orderUid);
}
