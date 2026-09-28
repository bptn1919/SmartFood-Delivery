package com.amomeal.marketplace.report.web;

import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.report.dto.DismissReportRequest;
import com.amomeal.marketplace.report.dto.LiftSuspensionRequest;
import com.amomeal.marketplace.report.dto.ManualSuspensionRequest;
import com.amomeal.marketplace.report.dto.ReportResponse;
import com.amomeal.marketplace.report.dto.SuspensionResponse;
import com.amomeal.marketplace.report.service.ReportMapper;
import com.amomeal.marketplace.report.service.ReportService;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Mirrors report/api.py::AdminReportController (mounted at /api/admin/reports, ADMIN group only). */
@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reportService;
    private final ReportMapper mapper;

    /** Django: GET /api/admin/reports (paginated; filters chef_id/status/category). */
    @GetMapping({"", "/"})
    public PageResponse<ReportResponse> listReports(@RequestParam(name = "chef_id", required = false) Long chefId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return reportService.listReports(chefId, status, category, page, pageSize);
    }

    /** Django: PATCH /{report_uid}/dismiss. */
    @PatchMapping("/{reportUid}/dismiss")
    public ReportResponse dismiss(@AuthenticationPrincipal CustomUser admin, @PathVariable UUID reportUid,
                                  @RequestBody(required = false) DismissReportRequest body) {
        String note = body == null ? "" : body.note();
        return mapper.toResponse(reportService.dismissReport(admin, reportUid, note));
    }

    /** Django: PATCH /{report_uid}/confirm - weight 5.0 + re-analyze. */
    @PatchMapping("/{reportUid}/confirm")
    public ReportResponse confirm(@AuthenticationPrincipal CustomUser admin, @PathVariable UUID reportUid) {
        return mapper.toResponse(reportService.confirmReport(admin, reportUid));
    }

    /** Django: GET /suspensions (paginated; filters chef_id/status). */
    @GetMapping("/suspensions")
    public PageResponse<SuspensionResponse> listSuspensions(@RequestParam(name = "chef_id", required = false) Long chefId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return reportService.listSuspensions(chefId, status, page, pageSize);
    }

    /** Django: POST /suspensions/manual. */
    @PostMapping("/suspensions/manual")
    public SuspensionResponse manualSuspension(@AuthenticationPrincipal CustomUser admin,
                                               @RequestBody ManualSuspensionRequest body) {
        return mapper.toResponse(reportService.manualSuspension(admin, body));
    }

    /** Django: PATCH /suspensions/{suspension_uid}/lift. */
    @PatchMapping("/suspensions/{suspensionUid}/lift")
    public SuspensionResponse lift(@AuthenticationPrincipal CustomUser admin, @PathVariable UUID suspensionUid,
                                   @RequestBody(required = false) LiftSuspensionRequest body) {
        String note = body == null ? "" : body.note();
        return mapper.toResponse(reportService.liftSuspension(admin, suspensionUid, note));
    }

    /** Django: PATCH /suspensions/{suspension_uid}/reject-appeal. */
    @PatchMapping("/suspensions/{suspensionUid}/reject-appeal")
    public SuspensionResponse rejectAppeal(@AuthenticationPrincipal CustomUser admin, @PathVariable UUID suspensionUid,
                                           @RequestBody(required = false) LiftSuspensionRequest body) {
        String note = body == null ? "" : body.note();
        return mapper.toResponse(reportService.rejectAppeal(admin, suspensionUid, note));
    }
}
