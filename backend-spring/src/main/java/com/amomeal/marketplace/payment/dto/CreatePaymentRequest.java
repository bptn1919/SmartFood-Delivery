package com.amomeal.marketplace.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Django {@code payment/schemas/requests.py::CreatePaymentRequest}. Only
 * {@code checkout_uid}/{@code payment_method}/{@code bank_code}/{@code language} are read by
 * the service; the buyer/items/invoice fields are accepted and ignored, exactly as Django's
 * {@code create_payment} ignores them (it passes {@code buyer_*=None} to PayOS).
 */
public record CreatePaymentRequest(
        @NotNull UUID checkoutUid,
        @NotNull String paymentMethod,
        String bankCode,
        String language,
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        String buyerAddress,
        String buyerCompanyName,
        String buyerTaxCode,
        List<Map<String, Object>> items,
        Map<String, Object> invoice,
        Long expiredAt) {
}
