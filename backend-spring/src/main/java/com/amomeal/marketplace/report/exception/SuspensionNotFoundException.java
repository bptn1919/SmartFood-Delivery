package com.amomeal.marketplace.report.exception;

import com.amomeal.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Mirrors exceptions/report.py::SuspensionNotFound. */
public class SuspensionNotFoundException extends ApiException {
    public SuspensionNotFoundException() {
        super(HttpStatus.NOT_FOUND, "SUSPENSION_NOT_FOUND", "Không tìm thấy lệnh khóa.");
    }
}
