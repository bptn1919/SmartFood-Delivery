package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.WalletTransactionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletTransactionStateRepository extends JpaRepository<WalletTransactionState, Long> {

    Optional<WalletTransactionState> findByWalletTransactionId(Long walletTransactionId);

    List<WalletTransactionState> findByWalletTransactionIdIn(Collection<Long> walletTransactionIds);
}
