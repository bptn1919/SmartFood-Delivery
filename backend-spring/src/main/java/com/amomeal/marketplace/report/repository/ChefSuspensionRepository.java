package com.amomeal.marketplace.report.repository;

import com.amomeal.marketplace.report.entity.ChefSuspension;
import com.amomeal.marketplace.report.entity.SuspensionStatus;
import com.amomeal.marketplace.report.entity.SuspensionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/report/queries.py (suspension half) + the ChefSuspension.objects.* calls in the services. */
public interface ChefSuspensionRepository extends JpaRepository<ChefSuspension, Long>, JpaSpecificationExecutor<ChefSuspension> {

    Optional<ChefSuspension> findByUid(UUID uid);

    /** Django: select_for_update().get(uid=, chef=). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChefSuspension s where s.uid = :uid and s.chef.id = :chefId")
    Optional<ChefSuspension> findForUpdateByUidAndChef(@Param("uid") UUID uid, @Param("chefId") Long chefId);

    /** Django: select_for_update().get(uid=). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChefSuspension s where s.uid = :uid")
    Optional<ChefSuspension> findForUpdateByUid(@Param("uid") UUID uid);

    /** Django: get_active_suspension (ACTIVE or APPEALING, newest first). */
    Optional<ChefSuspension> findFirstByChefIdAndStatusInOrderByCreatedAtDescIdDesc(Long chefId, Collection<SuspensionStatus> statuses);

    /** Django: filter(chef_id, status=ACTIVE).first() - unordered .first() = lowest pk. */
    Optional<ChefSuspension> findFirstByChefIdAndStatusOrderByIdAsc(Long chefId, SuspensionStatus status);

    /** Django: get_suspension_history. */
    List<ChefSuspension> findByChefIdOrderByCreatedAtDescIdDesc(Long chefId);

    List<ChefSuspension> findByChefIdAndStatusAndSuspensionType(Long chefId, SuspensionStatus status, SuspensionType type);
}
