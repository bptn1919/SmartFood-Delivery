package com.amomeal.marketplace.order.repository;

import com.amomeal.marketplace.order.entity.NotificationIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationIdempotencyKeyRepository extends JpaRepository<NotificationIdempotencyKey, Long> {

    Optional<NotificationIdempotencyKey> findByKey(String key);

    void deleteByKey(String key);

    boolean existsByKey(String key);
}
