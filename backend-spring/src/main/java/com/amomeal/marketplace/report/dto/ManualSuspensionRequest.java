package com.amomeal.marketplace.report.dto;

import com.amomeal.marketplace.report.entity.SuspensionType;
import com.amomeal.marketplace.report.exception.ReportValidationException;

import java.util.UUID;

/** Mirrors ManualSuspensionSchema and its validators. */
public record ManualSuspensionRequest(Long chefId, String suspensionType, UUID dishUid, String reason) {

    public record Validated(Long chefId, SuspensionType type, UUID dishUid, String reason) {
    }

    /** Field validators first (type enum, reason), then the model validator (dish_uid vs type) - as pydantic. */
    public Validated validated() {
        if (chefId == null) {
            throw new ReportValidationException("chef_id", "Field required");
        }
        SuspensionType type;
        try {
            type = suspensionType == null ? null : SuspensionType.valueOf(suspensionType);
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            throw new ReportValidationException("suspension_type", "Input should be 'DISH_LOCK' or 'FULL_LOCK'");
        }
        if (reason == null || reason.strip().isEmpty()) {
            throw new ReportValidationException("reason", "Lý do không được để trống.");
        }
        if (type == SuspensionType.DISH_LOCK && dishUid == null) {
            throw new ReportValidationException("body", "DISH_LOCK yêu cầu dish_uid.");
        }
        if (type == SuspensionType.FULL_LOCK && dishUid != null) {
            throw new ReportValidationException("body", "FULL_LOCK không được truyền dish_uid.");
        }
        return new Validated(chefId, type, dishUid, reason.strip());
    }
}
