package com.amomeal.marketplace.admin.repository;

import com.amomeal.marketplace.profile.entity.ChefPaymentInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** Admin-owned view over profile's {@link ChefPaymentInfo} (bank-account review list + verify). */
public interface AdminChefBankRepository
        extends JpaRepository<ChefPaymentInfo, Long>, JpaSpecificationExecutor<ChefPaymentInfo> {

    /** Django: {@code ChefPaymentInfo.objects.filter(pk=id, deleted=False).first()}. */
    Optional<ChefPaymentInfo> findByIdAndDeletedFalse(Long id);
}
