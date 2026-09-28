package com.amomeal.marketplace.report.dto;

import com.amomeal.marketplace.report.entity.ReportCategory;
import com.amomeal.marketplace.report.exception.ReportValidationException;
import com.amomeal.marketplace.report.service.ReportAnalysisService;

import java.util.UUID;

/**
 * Mirrors CreateReportSchema and its validators (description stripped, min 10 chars; target / evidence rules per
 * category). {@code category} is a plain string here so an unknown value is the project-wide 401 VALIDATION_ERROR
 * (as a pydantic enum failure is) instead of a JSON-mapping 500.
 */
public record CreateReportRequest(UUID orderUid, Long chefId, UUID dishUid, String category, String description,
                                  UUID evidenceUid) {

    public record Validated(ReportCategory category, String description) {
    }

    public Validated validated() {
        ReportCategory cat;
        try {
            cat = category == null ? null : ReportCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            cat = null;
        }
        if (cat == null) {
            throw new ReportValidationException("category", "Input should be a valid report category");
        }
        // field validator (runs before the model validator)
        if (description == null || description.isBlank()) {
            throw new ReportValidationException("description", "Mô tả không được để trống.");
        }
        if (description.strip().length() < 10) {
            throw new ReportValidationException("description", "Mô tả cần ít nhất 10 ký tự.");
        }
        // model validator
        if (ReportAnalysisService.ORDER_REQUIRED_CATEGORIES.contains(cat)) {
            if (orderUid == null) {
                throw new ReportValidationException("body", "Phản ánh loại này yêu cầu order_uid.");
            }
        } else {
            if (orderUid == null && chefId == null && dishUid == null) {
                throw new ReportValidationException("body", "Phản ánh loại này cần chef_id hoặc dish_uid.");
            }
            if (ReportAnalysisService.PLATFORM_EVIDENCE_REQUIRED.contains(cat) && evidenceUid == null) {
                throw new ReportValidationException("body", "Phản ánh loại này yêu cầu ảnh bằng chứng.");
            }
        }
        return new Validated(cat, description.strip());
    }
}
