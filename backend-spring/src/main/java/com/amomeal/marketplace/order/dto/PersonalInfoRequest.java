package com.amomeal.marketplace.order.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors ../../backend/order/schemas/requests.py::PersonalInfoSchema. */
public record PersonalInfoRequest(
        @NotBlank String fullName,
        @NotBlank String phoneNumber) {
}
