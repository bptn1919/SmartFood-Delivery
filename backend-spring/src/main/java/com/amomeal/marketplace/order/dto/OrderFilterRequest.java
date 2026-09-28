package com.amomeal.marketplace.order.dto;

/**
 * Mirrors ../../backend/order/schemas/requests.py::FilterOrderSchema +
 * OrderByOrderSchema, both bound from the query string by ninja's
 * {@code Query(...)}.
 *
 * <p>{@code search} matches on the accent-stripped DISH name of any item in the
 * order ({@code orderitem_fk_order__dish__name_no_accent__icontains}).
 * {@code status} silently degrades to "no filter" when it is not one of
 * {@code OrderStatusEnum}'s values (Django returns an empty {@code Q()} rather
 * than erroring) — preserved.
 *
 * <p>{@code orderBy} is a {@code Literal["created_at"]} in Django, so the only
 * degree of freedom is {@code sortType} (default DESC). The effective ordering
 * is {@code (created_at <dir>, uid DESC)}.
 */
public record OrderFilterRequest(String search, String status, String orderBy, String sortType) {

    public boolean ascending() {
        return "ASC".equalsIgnoreCase(sortType);
    }
}
