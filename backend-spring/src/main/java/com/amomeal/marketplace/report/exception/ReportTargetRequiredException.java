package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportTargetRequired. */
public class ReportTargetRequiredException extends ApiException {
    public ReportTargetRequiredException() {
        super(HttpStatus.BAD_REQUEST, "REPORT_TARGET_REQUIRED", "Phản ánh này cần order_uid hoặc chef_id/dish_uid hợp lệ.");
    }
}
