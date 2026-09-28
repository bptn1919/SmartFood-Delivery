package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::AppealNotAllowed. */
public class AppealNotAllowedException extends ApiException {
    public AppealNotAllowedException() {
        super(HttpStatus.BAD_REQUEST, "APPEAL_NOT_ALLOWED", "Lệnh khóa này không ở trạng thái cho phép giải trình.");
    }
}
