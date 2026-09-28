package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportRateLimitExceeded. */
public class ReportRateLimitExceededException extends ApiException {
    public ReportRateLimitExceededException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "REPORT_RATE_LIMIT", "Bạn đã gửi quá nhiều phản ánh trong 24 giờ. Vui lòng thử lại sau.");
    }
}
