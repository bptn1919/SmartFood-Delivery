package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportAlreadyExists. */
public class ReportAlreadyExistsException extends ApiException {
    public ReportAlreadyExistsException() {
        super(HttpStatus.CONFLICT, "REPORT_ALREADY_EXISTS", "Bạn đã gửi phản ánh cho đơn hàng này rồi.");
    }
}
