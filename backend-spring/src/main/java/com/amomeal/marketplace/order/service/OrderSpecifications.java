package com.amomeal.marketplace.order.service;

import com.amomeal.marketplace.ingredient.service.RemoveAccents;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderItem;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Arrays;
import java.util.Locale;

/**
 * Criteria port of ../../backend/order/orm/order.py::OrderORM.get_my_orders /
 * get_customer_orders plus ../../backend/order/schemas/requests.py::FilterOrderSchema.
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    /** Django: {@code Order.objects.exclude(status=DRAFT)}. */
    public static Specification<Order> notDraft() {
        return (root, q, cb) -> cb.notEqual(root.get("status"), OrderStatus.DRAFT);
    }

    public static Specification<Order> ownedBy(CustomUser user) {
        return (root, q, cb) -> cb.equal(root.get("owner").get("id"), user.getId());
    }

    public static Specification<Order> chefIs(CustomUser user) {
        return (root, q, cb) -> cb.equal(root.get("chef").get("id"), user.getId());
    }

    /**
     * Django: {@code filter_status} — a value outside {@code OrderStatusEnum} silently
     * becomes an empty {@code Q()} (no filter), never an error.
     */
    public static Specification<Order> hasStatus(String status) {
        if (status == null) {
            return null;
        }
        boolean valid = Arrays.stream(OrderStatus.values()).anyMatch(s -> s.name().equals(status));
        if (!valid) {
            return null;
        }
        return (root, q, cb) -> cb.equal(root.get("status"), OrderStatus.valueOf(status));
    }

    /**
     * Django: {@code filter_search} —
     * {@code Q(orderitem_fk_order__dish__name_no_accent__icontains=remove_accents(value))}.
     * Implemented as an EXISTS subquery so an order with two matching items is not
     * returned twice (Django's join can duplicate rows here; the list endpoints never
     * call {@code .distinct()} — PORT-NOTE: duplicates are not reproduced).
     */
    public static Specification<Order> searchDishName(String search) {
        if (search == null) {
            return null;
        }
        String needle = "%" + RemoveAccents.apply(search).toLowerCase(Locale.ROOT) + "%";
        return (root, q, cb) -> {
            Subquery<Long> sub = q.subquery(Long.class);
            var item = sub.from(OrderItem.class);
            Join<Object, Object> dish = item.join("dish", JoinType.INNER);
            sub.select(item.get("id")).where(
                    cb.equal(item.get("order"), root),
                    cb.like(cb.lower(dish.get("nameNoAccent")), needle));
            return cb.exists(sub);
        };
    }
}
