package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportTargetNotFound. */
public class ReportTargetNotFoundException extends ApiException {
    public ReportTargetNotFoundException() {
        super(HttpStatus.NOT_FOUND, "REPORT_TARGET_NOT_FOUND", "Không tìm thấy chef hoặc món ăn hợp lệ để phản ánh.");
    }
}
