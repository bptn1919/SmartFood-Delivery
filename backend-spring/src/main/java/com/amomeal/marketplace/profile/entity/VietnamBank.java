package com.amomeal.marketplace.profile.entity;

import java.util.Arrays;

/**
 * Mirrors ../../backend/utils/enums.py::VietnamBankEnum. Django exposes this as
 * a Pydantic enum field on {@code ChefPaymentInfoRequest.bank_name} (validated
 * at request-parsing time, before the service layer runs — a ninja
 * {@code ValidationError}, i.e. the project's 401 VALIDATION_ERROR quirk,
 * CLAUDE.md §6). This port keeps {@code ChefPaymentInfo.bankName} /
 * {@code ChefPaymentInfoRequest.bankName} as a plain {@code String} (avoiding
 * Jackson enum-deserialization edge cases) and validates membership in
 * {@link ChefPaymentService} instead — same outward behavior (unknown bank
 * name value is rejected), see
 * {@link com.amomeal.marketplace.profile.exception.ChefPaymentValidationException}.
 */
public enum VietnamBank {
    VIETCOMBANK("Vietcombank"),
    VCB("VCB"),
    TECHCOMBANK("Techcombank"),
    TCB("TCB"),
    VPBANK("VPBank"),
    BIDV("BIDV"),
    AGRIBANK("Agribank"),
    MBBANK("MBBank"),
    ACB("ACB"),
    SACOMBANK("Sacombank"),
    VIETINBANK("VietinBank"),
    TPBANK("TPBank"),
    HDBANK("HDBank"),
    SHB("SHB"),
    OCB("OCB"),
    MSB("MSB"),
    VIB("VIB"),
    LIENVIETPOSTBANK("LienVietPostBank"),
    SEABANK("SeABank"),
    BACABANK("BacABank"),
    PVCOMBANK("PVcomBank"),
    KIENLONGBANK("KienLongBank"),
    NCB("NCB");

    private final String value;

    VietnamBank(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static boolean isValid(String candidate) {
        return Arrays.stream(values()).anyMatch(b -> b.value.equals(candidate));
    }
}
