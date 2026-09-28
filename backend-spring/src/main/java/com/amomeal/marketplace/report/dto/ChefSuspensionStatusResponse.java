package com.amomeal.marketplace.report.dto;

/** Mirrors report/schemas/responses.py::ChefSuspensionStatusSchema (the chef's current lock status). */
public record ChefSuspensionStatusResponse(
        boolean isAcceptingOrders,
        String suspensionLevel,
        SuspensionResponse activeSuspension
) {
}
