package com.amomeal.marketplace.order.repository;

import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import com.amomeal.marketplace.voucher.entity.VoucherType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A SECOND Spring Data repository over {@code voucher.entity.AppliedVoucher},
 * owned by {@code order}.
 *
 * <p><b>Why a second repository instead of adding methods to
 * {@code voucher.repository.AppliedVoucherRepository}:</b> PROGRESS.md's
 * {@code voucher} seam note is explicit that {@code order} performs the
 * RESERVED-&gt;USED and RESERVED/USED-&gt;CANCELLED/EXPIRED transitions itself
 * (Django's {@code order/services/__init__.py} and {@code dish/tasks.py} write
 * them with raw ORM {@code .update(status=...)} calls; {@code voucher}'s own
 * service never does, in Django or here). Doing that "directly via the
 * repository" without editing a module this task may not modify means declaring
 * the queries {@code order} needs here — Spring Data happily supports several
 * repository interfaces over one entity, and it keeps the ownership boundary
 * honest: every method below corresponds to a line of {@code order}/{@code dish}
 * Django code, not to anything {@code voucher} itself does.
 */
public interface OrderAppliedVoucherRepository extends JpaRepository<AppliedVoucher, Long> {

    /**
     * Django: {@code _recalculate_order_total}'s single
     * {@code AppliedVoucher.objects.filter(checkout=checkout, status__in=[RESERVED, USED])}
     * load.
     */
    @Query("select a from AppliedVoucher a join fetch a.voucher "
            + "where a.checkoutUid = :checkoutUid and a.status in :statuses")
    List<AppliedVoucher> findByCheckoutUidAndStatusIn(@Param("checkoutUid") UUID checkoutUid,
                                                      @Param("statuses") Collection<VoucherReservationStatus> statuses);

    /** Django: the {@code AppliedVoucher.objects.filter(order=order, status__in=[...])} lookups. */
    @Query("select a from AppliedVoucher a join fetch a.voucher "
            + "where a.orderUid = :orderUid and a.status in :statuses")
    List<AppliedVoucher> findByOrderUidAndStatusIn(@Param("orderUid") UUID orderUid,
                                                   @Param("statuses") Collection<VoucherReservationStatus> statuses);

    /** Django: {@code apply_shop_voucher_to_order}'s "order đã có shop voucher rồi" pre-check. */
    boolean existsByOrderUidAndVoucherTypeAndStatusIn(UUID orderUid, VoucherType voucherType,
                                                      Collection<VoucherReservationStatus> statuses);

    /** Django: {@code apply_platform_voucher_to_checkout}'s "đã apply ... rồi" pre-check. */
    boolean existsByCheckoutUidAndVoucherTypeAndStatusIn(UUID checkoutUid, VoucherType voucherType,
                                                         Collection<VoucherReservationStatus> statuses);

    /**
     * Django: {@code place_order}'s COD branch —
     * {@code AppliedVoucher.objects.filter(Q(checkout=checkout) | Q(order__in=orders),
     * status=RESERVED).update(status=USED, reservation_expires_at=None)}.
     *
     * <p>Clearing {@code reservation_expires_at} matters: a USED row with a stale TTL
     * would otherwise still look expirable to {@code expire_old_reservations}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppliedVoucher a
               set a.status = com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.USED,
                   a.reservationExpiresAt = null,
                   a.updatedAt = CURRENT_TIMESTAMP
             where (a.checkoutUid = :checkoutUid or a.orderUid in :orderUids)
               and a.status = com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.RESERVED
            """)
    int markReservedAsUsed(@Param("checkoutUid") UUID checkoutUid, @Param("orderUids") Collection<UUID> orderUids);

    /**
     * Django: {@code cancel_order} step 6 —
     * {@code AppliedVoucher.objects.filter(order=order, status__in=[RESERVED, USED])
     * .update(status=CANCELLED)}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppliedVoucher a
               set a.status = com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.CANCELLED,
                   a.updatedAt = CURRENT_TIMESTAMP
             where a.orderUid = :orderUid
               and a.status in (com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.RESERVED,
                                com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.USED)
            """)
    int cancelForOrder(@Param("orderUid") UUID orderUid);

    /**
     * Django: {@code dish/tasks.py::release_expired_stock_holds} step 2 —
     * {@code AppliedVoucher.objects.filter(order=order, status__in=[RESERVED, USED])
     * .update(status=EXPIRED)}. Note EXPIRED, not CANCELLED: the sweep and a manual
     * cancellation are deliberately distinguishable in the ledger.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppliedVoucher a
               set a.status = com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.EXPIRED,
                   a.updatedAt = CURRENT_TIMESTAMP
             where a.orderUid = :orderUid
               and a.status in (com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.RESERVED,
                                com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.USED)
            """)
    int expireForOrder(@Param("orderUid") UUID orderUid);

    /**
     * Emulates the DB-level {@code on_delete=CASCADE} of Django's
     * {@code AppliedVoucher.order} FK for {@code checkout()}'s
     * {@code Order.objects.filter(owner=user, status=DRAFT).delete()}: Django's delete
     * takes those orders' AppliedVoucher rows with it. This port does NOT add that FK
     * (see V10__init_order.sql's comment — it would break voucher's own tests, which
     * reserve against synthetic order uids), so the cascade is performed explicitly.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AppliedVoucher a where a.orderUid in :orderUids")
    int deleteByOrderUidIn(@Param("orderUids") Collection<UUID> orderUids);
}
