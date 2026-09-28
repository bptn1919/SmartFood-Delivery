package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.WithdrawalFailureLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WithdrawalFailureLogRepository extends JpaRepository<WithdrawalFailureLog, Long> {

    List<WithdrawalFailureLog> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);
}
