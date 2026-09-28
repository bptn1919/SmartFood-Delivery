package com.amomeal.marketplace.order.entity;

/** Mirrors ../../backend/utils/enums.py::DeliveryTypeEnum. */
public enum DeliveryType {
    /** "Tự đến lấy" — customer picks the order up, delivery fee forced to 0. */
    SELF_PICKUP,
    /** "Dịch vụ vận chuyển thứ 3" — Django's default, fee quoted from Ahamove. */
    THIRD_PARTY
}
