package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.payment.entity.ChefCodBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChefCodBalanceRepository extends JpaRepository<ChefCodBalance, Long> {

    Optional<ChefCodBalance> findByChefId(Long chefId);

    /** Django {@code ChefCODBalance.objects.select_for_update().get(chef=chef_user)}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ChefCodBalance b where b.chefId = :chefId")
    Optional<ChefCodBalance> findForUpdateByChefId(@Param("chefId") Long chefId);
}
