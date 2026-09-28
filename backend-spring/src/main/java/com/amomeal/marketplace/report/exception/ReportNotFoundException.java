package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportNotFound. */
public class ReportNotFoundException extends ApiException {
    public ReportNotFoundException() {
        super(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "Không tìm thấy phản ánh.");
    }
}
