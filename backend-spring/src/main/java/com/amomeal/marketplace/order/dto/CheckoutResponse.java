package com.amomeal.marketplace.order.dto;

import com.amomeal.marketplace.order.entity.PaymentMethod;
import com.amomeal.marketplace.order.service.OrderPaymentGateway.PaymentSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors ../../backend/order/schemas/responses.py::CheckoutResponse.
 *
 * <p>The four payment fields are only populated by {@code place_order}'s PayOS
 * branch (Django mutates the response object after building it; a Java record is
 * immutable, hence {@link #withPayment}).
 */
public record CheckoutResponse(
        UUID uid,
        String fullName,
        String phoneNumber,
        LocalDate deliveryDate,
        LocalTime deliveryTime,
        String deliveryAddress,
        PaymentMethod paymentMethod,
        BigDecimal subTotal,
        BigDecimal taxAndFees,
        BigDecimal deliveryFee,
        BigDecimal platformSubtotalDiscount,
        BigDecimal platformShippingDiscount,
        BigDecimal totalDiscount,
        BigDecimal totalPrice,
        List<OrderResponse> orders,
        String paymentUrl,
        UUID paymentUid,
        String transactionId,
        String qrCode) {

    /** Django: the four {@code response.payment_* = ...} assignments in {@code place_order}'s PayOS branch. */
    public CheckoutResponse withPayment(PaymentSession session) {
        return new CheckoutResponse(uid, fullName, phoneNumber, deliveryDate, deliveryTime, deliveryAddress,
                paymentMethod, subTotal, taxAndFees, deliveryFee, platformSubtotalDiscount, platformShippingDiscount,
                totalDiscount, totalPrice, orders,
                session.paymentUrl(), session.paymentUid(), session.transactionId(), session.qrCode());
    }
}
