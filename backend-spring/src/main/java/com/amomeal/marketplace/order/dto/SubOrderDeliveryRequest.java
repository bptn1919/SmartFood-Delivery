package com.amomeal.marketplace.order.dto;

import com.amomeal.marketplace.order.entity.DeliveryType;
import jakarta.validation.constraints.NotNull;

/** Mirrors ../../backend/order/schemas/requests.py::SubOrderDeliverySchema. */
public record SubOrderDeliveryRequest(
        @NotNull Long chefId,
        @NotNull DeliveryType deliveryType) {
}
