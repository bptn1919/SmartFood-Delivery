package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.CustomerPaymentInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerPaymentInfoRepository extends JpaRepository<CustomerPaymentInfo, Long> {

    Optional<CustomerPaymentInfo> findByUserId(Long userId);
}
