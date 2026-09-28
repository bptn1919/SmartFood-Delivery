package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::ReportOrderNotOwned. */
public class ReportOrderNotOwnedException extends ApiException {
    public ReportOrderNotOwnedException() {
        super(HttpStatus.FORBIDDEN, "REPORT_ORDER_NOT_OWNED", "Bạn không phải chủ đơn hàng này.");
    }
}
