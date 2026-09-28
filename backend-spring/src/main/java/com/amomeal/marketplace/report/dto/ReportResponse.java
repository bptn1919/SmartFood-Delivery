package com.amomeal.marketplace.report.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors report/schemas/responses.py::ReportSchema. {@code order_id} is the order UUID: Django declares it
 * {@code Optional[int]} although Order's pk is a UUID (see ReportProperties#preserveOrderUuidBug).
 */
public record ReportResponse(
        UUID uid,
        Long chefId,
        UUID orderId,
        UUID dishUid,
        String category,
        String description,
        double credibilityWeight,
        String aiSeverity,
        Boolean aiFoodSafetyRisk,
        String aiSeverityReason,
        String status,
        String adminNote,
        Instant createdAt
) {
}
