package com.amomeal.marketplace.report.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Mirrors report/schemas/responses.py::SuspensionSchema (built by api.py::_suspension_to_schema). */
public record SuspensionResponse(
        UUID uid,
        Long chefId,
        String suspensionType,
        UUID lockedDishUid,
        String reason,
        String triggerSource,
        Map<String, Object> triggerData,
        String status,
        String appealText,
        Instant appealedAt,
        String liftNote,
        Instant liftedAt,
        Instant createdAt
) {
}
