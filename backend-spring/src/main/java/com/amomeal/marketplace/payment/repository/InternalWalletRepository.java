package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.InternalWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InternalWalletRepository extends JpaRepository<InternalWallet, Long> {

    Optional<InternalWallet> findByUserId(Long userId);

    /** Django: {@code InternalWallet.objects.select_for_update().get(user=user)}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from InternalWallet w where w.userId = :userId")
    Optional<InternalWallet> findForUpdateByUserId(@Param("userId") Long userId);

    /**
     * The create half of Django's {@code get_or_create(user=user)}: insert-if-absent, race-safe
     * (Django retries the get after an IntegrityError; ON CONFLICT is the same outcome).
     *
     * @return 1 if this call created the row, 0 if it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO internal_wallets (user_id, balance, pending_balance, currency, signature, created_at, updated_at)
            VALUES (:userId, 0, 0, 'VND', '', now(), now())
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId);
}
