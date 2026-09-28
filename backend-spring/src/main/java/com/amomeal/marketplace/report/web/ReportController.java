package com.amomeal.marketplace.report.web;

import com.amomeal.marketplace.report.dto.ChefSuspensionStatusResponse;
import com.amomeal.marketplace.report.dto.CreateReportRequest;
import com.amomeal.marketplace.report.dto.ReportResponse;
import com.amomeal.marketplace.report.dto.SubmitAppealRequest;
import com.amomeal.marketplace.report.dto.SuspensionResponse;
import com.amomeal.marketplace.report.service.ReportMapper;
import com.amomeal.marketplace.report.service.ReportService;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors report/api.py CustomerReportController + ChefReportController (both mounted at /api/report).
 *
 * <p>Role gates: customer endpoints = _require_customer_or_chef (CUSTOMER or CHEF group; an ADMIN-only user is
 * refused), chef endpoints = @require_group(CHEF). As everywhere in this port, the role check maps to hasRole(...)
 * and a refusal surfaces as the project-wide 401 (GlobalExceptionHandler, CLAUDE.md 4).
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final ReportMapper mapper;

    /** Django: POST /api/report - report a chef (with or without an order). */
    @PostMapping({"", "/"})
    @PreAuthorize("hasAnyRole('CUSTOMER','CHEF')")
    public ReportResponse createReport(@AuthenticationPrincipal CustomUser user, @RequestBody CreateReportRequest body) {
        return mapper.toResponse(reportService.createReport(user, body));
    }

    /** Django: GET /api/report/my-reports - reports I sent. */
    @GetMapping("/my-reports")
    @PreAuthorize("hasAnyRole('CUSTOMER','CHEF')")
    public List<ReportResponse> myReports(@AuthenticationPrincipal CustomUser user) {
        return reportService.reportsByCustomer(user.getId());
    }

    /** Django: GET /api/report - reports about my kitchen (CHEF). */
    @GetMapping({"", "/"})
    @PreAuthorize("hasRole('CHEF')")
    public List<ReportResponse> myReportsAsChef(@AuthenticationPrincipal CustomUser user) {
        return reportService.reportsAboutChef(user.getId());
    }

    /** Django: GET /api/report/suspension/current. */
    @GetMapping("/suspension/current")
    @PreAuthorize("hasRole('CHEF')")
    public ChefSuspensionStatusResponse currentSuspension(@AuthenticationPrincipal CustomUser user) {
        return reportService.currentSuspension(user);
    }

    /** Django: GET /api/report/suspension/history. */
    @GetMapping("/suspension/history")
    @PreAuthorize("hasRole('CHEF')")
    public List<SuspensionResponse> suspensionHistory(@AuthenticationPrincipal CustomUser user) {
        return reportService.suspensionHistory(user.getId());
    }

    /** Django: POST /api/report/suspension/{suspension_uid}/appeal. */
    @PostMapping("/suspension/{suspensionUid}/appeal")
    @PreAuthorize("hasRole('CHEF')")
    public SuspensionResponse submitAppeal(@AuthenticationPrincipal CustomUser user, @PathVariable UUID suspensionUid,
                                           @RequestBody SubmitAppealRequest body) {
        String text = body.validated();
        return mapper.toResponse(reportService.submitAppeal(user, suspensionUid, text));
    }
}
