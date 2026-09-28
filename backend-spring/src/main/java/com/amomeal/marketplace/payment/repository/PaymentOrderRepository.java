package com.amomeal.marketplace.payment.repository;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment module's own access to {@code order}'s {@link Order} rows — the same pattern
 * {@code order} used for {@code voucher}'s entity ({@code OrderAppliedVoucherRepository}), so
 * {@code order}'s source stays untouched. Each write mirrors one raw Django ORM statement in
 * ../../backend/payment/services.py.
 */
public interface PaymentOrderRepository extends Repository<Order, UUID> {

    /**
     * Django {@code _get_checkout_orders(checkout)}: select_related chef/checkout, prefetch
     * items+dish, {@code order_by("created_at")}.
     */
    @Query("""
            select distinct o from Order o
            join fetch o.checkout c
            left join fetch o.chef
            left join fetch o.owner
            left join fetch o.items i
            left join fetch i.dish
            where c.uid = :checkoutUid
            order by o.createdAt
            """)
    List<Order> findCheckoutOrders(@Param("checkoutUid") UUID checkoutUid);

    /** Django {@code Order.objects.select_related("checkout", "chef").get(uid=order_uid)}. */
    @Query("""
            select o from Order o
            join fetch o.checkout
            left join fetch o.chef
            left join fetch o.owner
            where o.uid = :uid
            """)
    Optional<Order> findWithCheckout(@Param("uid") UUID uid);

    /**
     * Django {@code _sync_successful_payment}:
     * {@code orders.update(status=CONFIRMED_SYSTEM, payment_status=HOLDING)} — a bulk UPDATE of
     * EVERY order of the checkout regardless of its current status (no state-machine check, no
     * {@code updated_at} bump: QuerySet.update skips auto_now). Preserved verbatim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o set o.status = :status, o.paymentStatus = :paymentStatus
             where o.checkout.uid = :checkoutUid
            """)
    int updateAllOfCheckout(@Param("checkoutUid") UUID checkoutUid, @Param("status") OrderStatus status,
                            @Param("paymentStatus") PaymentStatus paymentStatus);

    /** Django {@code Order.objects.filter(pk=order.pk).update(status=...)} (no updated_at bump). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = :status where o.uid = :uid")
    int updateStatusOnly(@Param("uid") UUID uid, @Param("status") OrderStatus status);

    /** Django {@code order.save(update_fields=["status", "payment_status", "updated_at"])}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o set o.status = :status, o.paymentStatus = :paymentStatus, o.updatedAt = CURRENT_TIMESTAMP
             where o.uid = :uid
            """)
    int updateStatusAndPaymentStatus(@Param("uid") UUID uid, @Param("status") OrderStatus status,
                                     @Param("paymentStatus") PaymentStatus paymentStatus);

    /** {@code (uid, status)} of every order of a checkout — a light read for the escrow book. */
    @Query("select o.uid, o.status from Order o where o.checkout.uid = :checkoutUid")
    List<Object[]> findUidAndStatusOfCheckout(@Param("checkoutUid") UUID checkoutUid);
}
