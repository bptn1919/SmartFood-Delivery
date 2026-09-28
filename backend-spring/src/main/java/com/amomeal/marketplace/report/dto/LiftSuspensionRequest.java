package com.amomeal.marketplace.report.dto;

/** Mirrors LiftSuspensionSchema ({@code lift_note} defaults to ""). Also the body of reject-appeal. */
public record LiftSuspensionRequest(String liftNote) {

    public String note() {
        return liftNote == null ? "" : liftNote;
    }
}
