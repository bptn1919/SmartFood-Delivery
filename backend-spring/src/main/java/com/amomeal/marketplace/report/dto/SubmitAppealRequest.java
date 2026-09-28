package com.amomeal.marketplace.report.dto;

import com.amomeal.marketplace.report.exception.ReportValidationException;

/** Mirrors SubmitAppealSchema: appeal_text stripped, at least 20 characters. */
public record SubmitAppealRequest(String appealText) {

    public String validated() {
        if (appealText == null || appealText.strip().length() < 20) {
            throw new ReportValidationException("appeal_text", "Giải trình cần ít nhất 20 ký tự.");
        }
        return appealText.strip();
    }
}
