package com.amomeal.marketplace.order.dto;

import java.util.List;

/**
 * Mirrors ../../backend/order/schemas/responses.py::ChefOrderGroupResponse.
 *
 * <p>PORT-NOTE: declared in Django's schema module and imported by
 * {@code order/api.py}, but never actually used as any endpoint's
 * {@code response=} — dead schema. Ported for completeness, likewise unused.
 */
public record ChefOrderGroupResponse(ChefInfoResponse chefInfo, List<OrderResponseWithInfo> orders) {
}
