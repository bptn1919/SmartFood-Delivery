package com.amomeal.marketplace.order.dto;

import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Mirrors ../../backend/order/schemas/responses.py::OrderResponeWithInfo (Django's spelling). */
public record OrderResponseWithInfo(
        UUID uid,
        String fullName,
        String phoneNumber,
        LocalDate deliveryDate,
        LocalTime deliveryTime,
        String deliveryAddress,
        Double deliveryLatitude,
        Double deliveryLongitude,
        String deliveryType,
        String chefName,
        String chefAddress,
        Double chefLatitude,
        Double chefLongitude,
        PaymentMethod paymentMethod,
        BigDecimal subTotal,
        BigDecimal taxAndFees,
        BigDecimal deliveryFee,
        BigDecimal platformSubtotalDiscount,
        BigDecimal platformShippingDiscount,
        BigDecimal shopDiscount,
        BigDecimal totalDiscount,
        BigDecimal totalPrice,
        List<OrderItemResponse> items,
        OrderStatus status,
        String refundStatus,
        String voucherCode) {
}
