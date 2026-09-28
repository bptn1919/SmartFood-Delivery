package com.amomeal.marketplace.payment.dto;

import java.util.List;

/** Django {@code PaymentInvoicesResponse}. */
public record PaymentInvoicesResponse(boolean success, List<PaymentInvoiceInfo> invoices, String error) {
}
