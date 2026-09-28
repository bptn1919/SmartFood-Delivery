package com.amomeal.marketplace.order.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors ../../backend/order/schemas/requests.py::ApplyVoucherSchema. */
public record ApplyVoucherRequest(@NotBlank String voucherCode) {
}
