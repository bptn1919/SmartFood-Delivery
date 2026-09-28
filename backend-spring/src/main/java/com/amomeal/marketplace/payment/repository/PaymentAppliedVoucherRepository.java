package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

/**
 * The payment module's writes to {@code voucher}'s AppliedVoucher ledger — each one a raw
 * {@code AppliedVoucher.objects.filter(...).update(...)} in ../../backend/payment/services.py
 * (QuerySet.update: no {@code updated_at} bump, preserved). Callers guard empty collections
 * (an empty {@code IN ()} is invalid SQL).
 */
public interface PaymentAppliedVoucherRepository extends Repository<AppliedVoucher, Long> {

    /** {@code _sync_successful_payment}: the checkout's RESERVED rows -> USED, {@code reservation_expires_at=None}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppliedVoucher a set a.status = :used, a.reservationExpiresAt = null
             where a.checkoutUid = :checkoutUid and a.status = :reserved
            """)
    int markReservedUsedForCheckout(@Param("checkoutUid") UUID checkoutUid,
                                    @Param("reserved") VoucherReservationStatus reserved,
                                    @Param("used") VoucherReservationStatus used);

    /** {@code _sync_successful_payment}: {@code filter(order__uid__in=refunded).update(status=CANCELLED)} — ANY status. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AppliedVoucher a set a.status = :status where a.orderUid in :orderUids")
    int updateStatusForOrders(@Param("orderUids") Collection<UUID> orderUids,
                              @Param("status") VoucherReservationStatus status);

    /**
     * {@code _cancel_orders_for_payment}: {@code filter(Q(checkout=...) | Q(order__in=orders),
     * status__in=[RESERVED, USED]).update(status=CANCELLED)}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppliedVoucher a set a.status = :status
             where (a.checkoutUid = :checkoutUid or a.orderUid in :orderUids)
               and a.status in :fromStatuses
            """)
    int updateStatusForCheckoutOrOrders(@Param("checkoutUid") UUID checkoutUid,
                                        @Param("orderUids") Collection<UUID> orderUids,
                                        @Param("fromStatuses") Collection<VoucherReservationStatus> fromStatuses,
                                        @Param("status") VoucherReservationStatus status);
}
