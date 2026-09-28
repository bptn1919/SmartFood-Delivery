package com.amomeal.marketplace.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Django {@code PaymentInvoiceInfo} — its keys are camelCase in Django too, hence the explicit names. */
public record PaymentInvoiceInfo(
        @JsonProperty("invoiceId") String invoiceId,
        @JsonProperty("invoiceNumber") String invoiceNumber,
        @JsonProperty("issuedTimestamp") Long issuedTimestamp,
        @JsonProperty("issuedDatetime") String issuedDatetime,
        @JsonProperty("transactionId") String transactionId,
        @JsonProperty("reservationCode") String reservationCode,
        @JsonProperty("codeOfTax") String codeOfTax) {
}
