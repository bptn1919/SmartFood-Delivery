package com.amomeal.marketplace.order.repository;

import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import com.amomeal.marketplace.order.entity.PaymentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mirrors the {@code Order.objects.*} half of ../../backend/order/orm/order.py::OrderORM. */
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /**
     * Django: {@code OrderORM.get_order_by_uid} — the {@code select_related}/
     * {@code prefetch_related} set is reproduced as JOIN FETCHes because
     * {@code spring.jpa.open-in-view=false} means anything not fetched inside the
     * transaction is unreadable afterwards (and {@code place_order} deliberately
     * runs OUTSIDE one — see {@code OrderService.placeOrder}).
     */
    @Query("""
            select distinct o from Order o
            join fetch o.checkout c
            left join fetch c.deliveryAddress
            left join fetch o.chef
            left join fetch o.owner
            left join fetch o.items i
            left join fetch i.dish d
            left join fetch d.owner
            left join fetch d.attachment
            where o.uid = :uid
            """)
    Optional<Order> findDetailedByUid(@Param("uid") UUID uid);

    /** Django: {@code OrderORM.get_orders_by_checkout_uid} — {@code .order_by('created_at')}. */
    @Query("""
            select distinct o from Order o
            join fetch o.checkout c
            left join fetch c.deliveryAddress
            left join fetch o.chef
            left join fetch o.owner
            left join fetch o.items i
            left join fetch i.dish d
            left join fetch d.owner
            left join fetch d.attachment
            where c.uid = :checkoutUid
            order by o.createdAt
            """)
    List<Order> findDetailedByCheckoutUid(@Param("checkoutUid") UUID checkoutUid);

    List<Order> findByCheckoutUidOrderByCreatedAtAsc(UUID checkoutUid);

    /** Django: the {@code Order.objects.filter(owner=user, status=DRAFT).delete()} cleanup in {@code checkout()}. */
    List<Order> findByOwnerAndStatus(CustomUser owner, OrderStatus status);

    /**
     * Django: {@code Order.objects.select_for_update().filter(uid=order_uid).first()} in
     * {@code dish/tasks.py::release_expired_stock_holds} — a genuine pessimistic row lock,
     * so two sweep ticks (or a sweep and a manual cancel) cannot both decide to cancel.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.uid = :uid")
    Optional<Order> findForUpdateByUid(@Param("uid") UUID uid);

    /**
     * Django: {@code OrderORM.place_order(order)} —
     * {@code order.status = PENDING; order.save(update_fields=["status", "delivery_name",
     * "delivery_phone", "delivery_address_text", "delivery_latitude", "delivery_longitude"])}.
     * A targeted UPDATE (own transaction) rather than a merge of a detached entity, because
     * {@code place_order} runs outside any transaction by design (see OrderService.placeOrder).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o set o.status = :status, o.deliveryName = :name, o.deliveryPhone = :phone,
                   o.deliveryAddressText = :addressText, o.deliveryLatitude = :lat, o.deliveryLongitude = :lng,
                   o.updatedAt = CURRENT_TIMESTAMP
             where o.uid = :uid
            """)
    int markPlaced(@Param("uid") UUID uid, @Param("status") OrderStatus status, @Param("name") String name,
                   @Param("phone") String phone, @Param("addressText") String addressText,
                   @Param("lat") Double lat, @Param("lng") Double lng);

    /** Django: {@code order.save(update_fields=["status"])}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = :status, o.updatedAt = CURRENT_TIMESTAMP where o.uid = :uid")
    int updateStatus(@Param("uid") UUID uid, @Param("status") OrderStatus status);

    /** Django: {@code order.save(update_fields=["payment_status"])}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.paymentStatus = :ps, o.updatedAt = CURRENT_TIMESTAMP where o.uid = :uid")
    int updatePaymentStatus(@Param("uid") UUID uid, @Param("ps") PaymentStatus paymentStatus);
}
