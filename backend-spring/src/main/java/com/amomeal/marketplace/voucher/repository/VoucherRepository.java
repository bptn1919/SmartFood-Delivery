package com.amomeal.marketplace.voucher.repository;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/voucher/orm/voucher.py::VoucherORM (the Voucher-table half). */
public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    /**
     * Django: {@code Voucher.objects.get(code=code.upper())} — relies on the app-layer
     * {@code check_code_exists} enforcing GLOBAL code uniqueness at create time (the DB
     * constraint itself is only per-chef, see V9 migration), so at most one row is expected
     * to ever match here in practice.
     */
    Optional<Voucher> findFirstByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndUidNot(String code, UUID excludeUid);

    /** Django: {@code VoucherORM.get_vouchers_by_chef}. */
    List<Voucher> findByChefOrderByCreatedAtDesc(CustomUser chef);

    /** Django: {@code VoucherORM.get_active_vouchers_by_chef}. */
    @Query("select v from Voucher v where v.chef.id = :chefId and v.isActive = true "
            + "and v.startDate <= :now and v.endDate >= :now order by v.createdAt desc")
    List<Voucher> findActiveByChef(@Param("chefId") Long chefId, @Param("now") Instant now);

    /** Django: {@code VoucherORM.get_voucher_with_lock} — {@code select_for_update()}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where upper(v.code) = upper(:code) and v.voucherType = :voucherType "
            + "and v.isActive = true")
    Optional<Voucher> findForUpdateByCodeAndVoucherType(@Param("code") String code,
                                                         @Param("voucherType") VoucherType voucherType);

    /** Django: {@code VoucherORM.get_shop_voucher_with_lock}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where upper(v.code) = upper(:code) and v.chef = :chef "
            + "and v.voucherType = com.amomeal.marketplace.voucher.entity.VoucherType.SHOP_VOUCHER "
            + "and v.isActive = true")
    Optional<Voucher> findShopVoucherForUpdate(@Param("code") String code, @Param("chef") CustomUser chef);
}
