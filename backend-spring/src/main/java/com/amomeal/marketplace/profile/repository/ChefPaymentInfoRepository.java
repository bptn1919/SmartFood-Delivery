package com.amomeal.marketplace.profile.repository;

import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChefPaymentInfoRepository extends JpaRepository<ChefPaymentInfo, Long> {

    /** Mirrors ChefPaymentInfoORM.get_by_user — no `deleted` filter in Django either. */
    Optional<ChefPaymentInfo> findByUser(CustomUser user);
}
