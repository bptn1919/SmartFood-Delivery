package com.amomeal.marketplace.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Mirrors ../../backend/order/schemas/requests.py::UpdateDeliveryTypesPayload. */
public record UpdateDeliveryTypesRequest(@NotNull @Valid List<SubOrderDeliveryRequest> subOrders) {
}
