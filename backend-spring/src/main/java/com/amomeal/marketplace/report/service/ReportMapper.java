package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.report.config.ReportProperties;
import com.amomeal.marketplace.report.dto.ReportResponse;
import com.amomeal.marketplace.report.dto.SuspensionResponse;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ChefSuspension;
import org.springframework.stereotype.Component;

/** Django: ReportSchema.from_orm_dish_uid / api.py::_suspension_to_schema. Reads scalar columns only (no lazy proxies). */
@Component
public class ReportMapper {

    private final ReportProperties properties;

    public ReportMapper(ReportProperties properties) {
        this.properties = properties;
    }

    public ReportResponse toResponse(ChefReport r) {
        if (properties.isPreserveOrderUuidBug() && r.getOrderUid() != null) {
            // PORT-NOTE: Django's ReportSchema.order_id is Optional[int] but Order's pk is a UUID, so pydantic raises
            // for every report that has an order -> uncaught -> 500 (after the report was already saved on POST).
            throw new IllegalStateException("ReportSchema.order_id: UUID is not a valid integer (Django bug preserved)");
        }
        return new ReportResponse(r.getUid(), r.getChefId(), r.getOrderUid(), r.getDishUid(),
                r.getCategory().name(), r.getDescription(), r.getCredibilityWeight(), r.getAiSeverity(),
                r.getAiFoodSafetyRisk(), r.getAiSeverityReason(), r.getStatus().name(), r.getAdminNote(),
                r.getCreatedAt());
    }

    public SuspensionResponse toResponse(ChefSuspension s) {
        return new SuspensionResponse(s.getUid(), s.getChefId(), s.getSuspensionType().name(), s.getLockedDishUid(),
                s.getReason(), s.getTriggerSource().name(), s.getTriggerData(), s.getStatus().name(),
                s.getAppealText(), s.getAppealedAt(), s.getLiftNote(), s.getLiftedAt(), s.getCreatedAt());
    }
}
