package com.amomeal.marketplace.voucher.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Mirrors ../../backend/voucher/schemas/request.py::ValidateVoucherSchema. */
public record ValidateVoucherRequest(
        @NotBlank String code,
        @NotNull Long chefId,
        @NotNull BigDecimal orderAmount
) {
}
