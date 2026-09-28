package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportEvidenceRequired. */
public class ReportEvidenceRequiredException extends ApiException {
    public ReportEvidenceRequiredException() {
        super(HttpStatus.BAD_REQUEST, "REPORT_EVIDENCE_REQUIRED", "Phản ánh loại này yêu cầu ảnh bằng chứng.");
    }
}
