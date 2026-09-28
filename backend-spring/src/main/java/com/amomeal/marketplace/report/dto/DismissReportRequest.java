package com.amomeal.marketplace.report.dto;

/** Mirrors DismissReportSchema ({@code admin_note} defaults to ""). */
public record DismissReportRequest(String adminNote) {

    public String note() {
        return adminNote == null ? "" : adminNote;
    }
}
