package com.amomeal.marketplace.voucher.repository;

import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.voucher.entity.AppliedVoucher;
import com.amomeal.marketplace.voucher.entity.Voucher;
import com.amomeal.marketplace.voucher.entity.VoucherReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/voucher/orm/voucher.py::VoucherORM (the AppliedVoucher-table half). */
public interface AppliedVoucherRepository extends JpaRepository<AppliedVoucher, Long> {

    /**
     * Django: {@code VoucherORM.get_voucher_usage_count_by_user} —
     * {@code AppliedVoucher.objects.filter(voucher=voucher, user=user).count()}.
     * <b>Deliberately NOT status-filtered</b> (unlike {@link #countByVoucherAndUserAndStatusIn}) —
     * this counts CANCELLED/EXPIRED rows too. Used by {@code validate_voucher_for_order} and
     * {@code get_available_vouchers_by_chef}; the reservation-apply flows use the
     * status-filtered sibling instead. Two genuinely different counting rules for the same
     * "has this user used up their per-user limit" question — preserved as Django has it,
     * not unified.
     */
    long countByVoucherAndUser(Voucher voucher, CustomUser user);

    /** Django: {@code VoucherORM.count_active_reservations} (RESERVED + USED). */
    long countByVoucherAndStatusIn(Voucher voucher, Collection<VoucherReservationStatus> statuses);

    /** Django: {@code VoucherORM.count_user_active_reservations} (RESERVED + USED). */
    long countByVoucherAndUserAndStatusIn(Voucher voucher, CustomUser user, Collection<VoucherReservationStatus> statuses);

    /** Django: the inline filter in {@code apply_platform_voucher_reservation}. */
    Optional<AppliedVoucher> findFirstByVoucherAndCheckoutUidAndUserAndStatus(
            Voucher voucher, UUID checkoutUid, CustomUser user, VoucherReservationStatus status);

    /** Django: the inline filter in {@code apply_shop_voucher_reservation}. */
    Optional<AppliedVoucher> findFirstByVoucherAndOrderUidAndUserAndStatus(
            Voucher voucher, UUID orderUid, CustomUser user, VoucherReservationStatus status);

    /**
     * Django: {@code VoucherORM.expire_old_reservations} —
     * {@code AppliedVoucher.objects.filter(voucher=voucher, status=RESERVED,
     * reservation_expires_at__lt=now).update(status=EXPIRED)}. Called inline at the start of
     * every reservation attempt (not a scheduled job — see {@code VoucherService}'s javadoc).
     */
    @Modifying
    @Query("update AppliedVoucher a set a.status = com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.EXPIRED "
            + "where a.voucher = :voucher and a.status = com.amomeal.marketplace.voucher.entity.VoucherReservationStatus.RESERVED "
            + "and a.reservationExpiresAt < :now")
    int expireOldReservations(@Param("voucher") Voucher voucher, @Param("now") Instant now);

    /**
     * Django: the {@code Sum("discount_amount")} groupby in
     * {@code VoucherService._calculate_net_subtotal}, restricted to SHOP_VOUCHER
     * RESERVED/USED rows for the given orders. Owned entirely by {@code voucher} — no
     * {@code order.Order}/{@code Checkout} entity needed, just the order uids the future
     * {@code order} module passes in (see {@code VoucherService.calculateNetSubtotal}).
     */
    @Query("select a.orderUid as orderUid, sum(a.discountAmount) as total from AppliedVoucher a "
            + "where a.orderUid in :orderUids "
            + "and a.voucherType = com.amomeal.marketplace.voucher.entity.VoucherType.SHOP_VOUCHER "
            + "and a.status in :statuses group by a.orderUid")
    List<OrderDiscountTotal> sumShopDiscountByOrderUidIn(@Param("orderUids") Collection<UUID> orderUids,
                                                          @Param("statuses") Collection<VoucherReservationStatus> statuses);

    interface OrderDiscountTotal {
        UUID getOrderUid();

        BigDecimal getTotal();
    }
}
