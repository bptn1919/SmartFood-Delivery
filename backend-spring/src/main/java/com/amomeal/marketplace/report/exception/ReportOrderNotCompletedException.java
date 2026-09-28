package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportOrderNotCompleted. */
public class ReportOrderNotCompletedException extends ApiException {
    public ReportOrderNotCompletedException() {
        super(HttpStatus.BAD_REQUEST, "REPORT_ORDER_NOT_COMPLETED", "Chỉ có thể phản ánh sau khi đơn hàng hoàn thành.");
    }
}
