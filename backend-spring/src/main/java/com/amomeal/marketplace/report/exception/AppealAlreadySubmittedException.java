package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::AppealAlreadySubmitted. */
public class AppealAlreadySubmittedException extends ApiException {
    public AppealAlreadySubmittedException() {
        super(HttpStatus.CONFLICT, "APPEAL_ALREADY_SUBMITTED", "Bạn đã gửi giải trình cho lệnh khóa này rồi.");
    }
}
