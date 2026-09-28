package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.ingredient.dto.PageResponse;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.report.config.ReportProperties;
import com.amomeal.marketplace.report.dto.ChefSuspensionStatusResponse;
import com.amomeal.marketplace.report.dto.CreateReportRequest;
import com.amomeal.marketplace.report.dto.ManualSuspensionRequest;
import com.amomeal.marketplace.report.dto.ReportResponse;
import com.amomeal.marketplace.report.dto.SuspensionResponse;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ChefSuspension;
import com.amomeal.marketplace.report.entity.ChefWarning;
import com.amomeal.marketplace.report.entity.ReportCategory;
import com.amomeal.marketplace.report.entity.ReportStatus;
import com.amomeal.marketplace.report.entity.SuspensionStatus;
import com.amomeal.marketplace.report.entity.SuspensionType;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ChefSuspensionRepository;
import com.amomeal.marketplace.report.service.ReportCommandService.CreatedReport;
import com.amomeal.marketplace.report.service.ReportCommandService.SuspensionOutcome;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Orchestrator - port of ../../backend/report/services/report_service.py (flow) + report/queries.py + api.py glue.
 *
 * <p><b>Deliberately NOT {@code @Transactional}</b> (CLAUDE.md 8b): Django commits {@code create_report} first
 * and then runs Gemini, {@code analyze_and_act} and the emails OUTSIDE the transaction ("Gemini/email errors do not
 * roll the report back"). Every DB write is delegated to {@link ReportCommandService} (one transaction per call);
 * {@code transaction.on_commit} email hooks simply run after that call returns.
 */
@Slf4j
@Service
public class ReportService {

    private final ReportCommandService commands;
    private final ReportAnalysisService analysis;
    private final GeminiSeverityService gemini;
    private final ReportEmailService emails;
    private final ReportMapper mapper;
    private final ReportProperties properties;
    private final ChefReportRepository reportRepository;
    private final ChefSuspensionRepository suspensionRepository;
    private final ChefProfileRepository chefProfileRepository;

    public ReportService(ReportCommandService commands, ReportAnalysisService analysis, GeminiSeverityService gemini,
                         ReportEmailService emails, ReportMapper mapper, ReportProperties properties,
                         ChefReportRepository reportRepository, ChefSuspensionRepository suspensionRepository,
                         ChefProfileRepository chefProfileRepository) {
        this.commands = commands;
        this.analysis = analysis;
        this.gemini = gemini;
        this.emails = emails;
        this.mapper = mapper;
        this.properties = properties;
        this.reportRepository = reportRepository;
        this.suspensionRepository = suspensionRepository;
        this.chefProfileRepository = chefProfileRepository;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Customer
    // ------------------------------------------------------------------------------------------------------------

    /** Django: create_report (atomic) then run_post_create (outside the transaction). */
    public ChefReport createReport(CustomUser reporter, CreateReportRequest request) {
        CreateReportRequest.Validated v = request.validated();
        CreatedReport created = commands.create(reporter, request.orderUid(), request.dishUid(), request.chefId(),
                v.category(), v.description(), request.evidenceUid());
        runPostCreate(created.report(), created.chefId(), created.dish());
        return created.report();
    }

    /** Django: run_post_create - Gemini (food-quality only) + route to the per-category handler. */
    void runPostCreate(ChefReport report, Long chefId, Dish dish) {
        ReportCategory category = report.getCategory();
        if (ReportAnalysisService.FOOD_QUALITY_CATEGORIES.contains(category)) {
            try {
                GeminiSeverityService.SeverityResult r = gemini.analyze(category.name(), report.getDescription());
                double newWeight = ReportAnalysisService.computeWeightAfterAi(
                        report.getCredibilityWeight(), r.severity(), r.foodSafetyRisk());
                commands.applyAiResult(report.getId(), r.severity(), r.foodSafetyRisk(), r.reason(), newWeight);
                report.setCredibilityWeight(newWeight);
                report.setAiSeverity(r.severity());
                report.setAiFoodSafetyRisk(r.foodSafetyRisk());
                report.setAiSeverityReason(r.reason());
            } catch (Exception ex) {
                // A failure here (e.g. severity longer than the column) never rolls back / fails the report.
                log.warn("Gemini severity call failed for report {}: {}", report.getUid(), ex.toString());
            }
        }
        if (ReportAnalysisService.FOOD_QUALITY_CATEGORIES.contains(category)) {
            analyzeAndAct(chefId, dish);
        } else if (ReportAnalysisService.DELIVERY_CATEGORIES.contains(category)) {
            analyzeDeliveryAndAct(chefId);
        } else if (ReportAnalysisService.FINANCIAL_CATEGORIES.contains(category)) {
            handleFinancialReport(report, chefId);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Analysis & lock
    // ------------------------------------------------------------------------------------------------------------

    /** Django: analyze_and_act - food quality: metrics -> decision -> WARNING / DISH_LOCK / FULL_LOCK. Never downgrades a FULL_LOCK. */
    public void analyzeAndAct(Long chefId, Dish dish) {
        Map<String, Object> metrics = analysis.computeChefMetrics(chefId, dish == null ? null : dish.getUid());
        ReportAnalysisService.Decision decision = ReportAnalysisService.decideAction(metrics);
        String action = decision.action();
        if ("NONE".equals(action)) {
            return;
        }
        Optional<ChefSuspension> active = suspensionRepository
                .findFirstByChefIdAndStatusOrderByIdAsc(chefId, SuspensionStatus.ACTIVE);
        if (active.isPresent() && active.get().getSuspensionType() == SuspensionType.FULL_LOCK) {
            return;
        }
        if ("FULL_LOCK".equals(action)) {
            ChefSuspension s = commands.applyFullLock(chefId, decision.reason(), metrics);
            emails.sendFullLock(chefId, s);
        } else if ("DISH_LOCK".equals(action) && dish != null) {
            if (active.isPresent() && Objects.equals(active.get().getLockedDishUid(), dish.getUid())) {
                return; // dish already locked
            }
            ChefSuspension s = commands.applyDishLock(chefId, dish, decision.reason(), metrics);
            emails.sendDishLock(chefId, dish.getName(), s);
        } else if ("WARNING".equals(action)) {
            applyWarning(chefId, dish, decision.reason(), metrics);
        }
    }

    /** Django: _apply_warning. */
    private void applyWarning(Long chefId, Dish dish, String reason, Map<String, Object> metrics) {
        Optional<ChefWarning> warning = commands.createFoodQualityWarning(chefId, dish, metrics);
        if (warning.isEmpty()) {
            return;
        }
        // Django's _send_warning_email swallows every failure, so email_sent is always set afterwards.
        emails.sendWarning(chefId, dish == null ? null : dish.getName(), reason);
        commands.markWarningEmailSent(warning.get().getId());
    }

    /** Django: _analyze_delivery_and_act - warning only, never a suspension. */
    void analyzeDeliveryAndAct(Long chefId) {
        Map<String, Object> metrics = analysis.computeDeliveryMetrics(chefId);
        ReportAnalysisService.Decision decision = ReportAnalysisService.decideDeliveryAction(metrics);
        if ("NONE".equals(decision.action())) {
            return;
        }
        Optional<ChefWarning> warning = commands.createDeliveryWarning(chefId, metrics);
        if (warning.isEmpty()) {
            return;
        }
        emails.sendDeliveryWarning(chefId, decision.reason(), metrics, "ADMIN_ALERT".equals(decision.action()));
        commands.markWarningEmailSent(warning.get().getId());
    }

    /** Django: _handle_financial_report - no threshold; recorded immediately + emails. */
    void handleFinancialReport(ChefReport report, Long chefId) {
        if (properties.isPreserveOrderUuidBug() && report.getOrderUid() != null) {
            // PORT-NOTE: Django puts the order UUID into a JSONField -> TypeError (uncaught) -> the request 500s.
            throw new IllegalStateException("Object of type UUID is not JSON serializable (Django bug preserved)");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("report_uid", report.getUid().toString());
        String description = report.getDescription();
        snapshot.put("description", description.length() > 200 ? description.substring(0, 200) : description);
        snapshot.put("order_id", report.getOrderUid() == null ? null : report.getOrderUid().toString());
        ChefWarning warning = commands.createFinancialWarning(chefId, snapshot);
        emails.sendFinancialAlerts(chefId, report, report.getOrderUid() == null ? null : report.getOrderUid().toString());
        commands.markWarningEmailSent(warning.getId());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Chef
    // ------------------------------------------------------------------------------------------------------------

    public ChefSuspension submitAppeal(CustomUser chef, UUID suspensionUid, String appealText) {
        return commands.submitAppeal(chef, suspensionUid, appealText);
    }

    /** Django: current_suspension - {@code request.user.chef_profile} raises (500) when the chef has no profile. */
    public ChefSuspensionStatusResponse currentSuspension(CustomUser chef) {
        ChefProfile profile = chefProfileRepository.findByUserId(chef.getId()).orElseThrow();
        Optional<ChefSuspension> active = suspensionRepository.findFirstByChefIdAndStatusInOrderByCreatedAtDescIdDesc(
                chef.getId(), List.of(SuspensionStatus.ACTIVE, SuspensionStatus.APPEALING));
        return new ChefSuspensionStatusResponse(profile.isAcceptingOrders(), profile.getSuspensionLevel().name(),
                active.map(mapper::toResponse).orElse(null));
    }

    public List<SuspensionResponse> suspensionHistory(Long chefId) {
        return suspensionRepository.findByChefIdOrderByCreatedAtDescIdDesc(chefId).stream().map(mapper::toResponse).toList();
    }

    public List<ReportResponse> reportsAboutChef(Long chefId) {
        return reportRepository.findByChefIdAndDeletedFalseOrderByCreatedAtDesc(chefId).stream()
                .map(mapper::toResponse).toList();
    }

    public List<ReportResponse> reportsByCustomer(Long reporterId) {
        return reportRepository.findByReporterIdAndDeletedFalseOrderByCreatedAtDesc(reporterId).stream()
                .map(mapper::toResponse).toList();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------------------------------------------------

    /** Django: get_all_reports + paginate. Unknown status/category strings match nothing (as in Django). */
    public PageResponse<ReportResponse> listReports(Long chefId, String status, String category, int page, int pageSize) {
        Specification<ChefReport> spec = (root, q, cb) -> cb.isFalse(root.get("deleted"));
        if (chefId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("chefId"), chefId));
        }
        if (status != null && !status.isEmpty()) {
            ReportStatus s = parseEnum(ReportStatus.class, status);
            if (s == null) {
                return page(List.of(), page, pageSize, 0, Function.identity());
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        if (category != null && !category.isEmpty()) {
            ReportCategory c = parseEnum(ReportCategory.class, category);
            if (c == null) {
                return page(List.of(), page, pageSize, 0, Function.identity());
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("category"), c));
        }
        long total = reportRepository.count(spec);
        Page<ChefReport> content = reportRepository.findAll(spec, pageRequest(page, pageSize));
        return page(content.getContent(), page, pageSize, total, mapper::toResponse);
    }

    /** Django: get_all_suspensions + paginate. */
    public PageResponse<SuspensionResponse> listSuspensions(Long chefId, String status, int page, int pageSize) {
        Specification<ChefSuspension> spec = (root, q, cb) -> cb.conjunction();
        if (chefId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("chefId"), chefId));
        }
        if (status != null && !status.isEmpty()) {
            SuspensionStatus s = parseEnum(SuspensionStatus.class, status);
            if (s == null) {
                return page(List.of(), page, pageSize, 0, Function.identity());
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        long total = suspensionRepository.count(spec);
        Page<ChefSuspension> content = suspensionRepository.findAll(spec, pageRequest(page, pageSize));
        return page(content.getContent(), page, pageSize, total, mapper::toResponse);
    }

    public ChefReport dismissReport(CustomUser admin, UUID reportUid, String adminNote) {
        return commands.dismiss(admin, reportUid, adminNote);
    }

    /** Django: admin_confirm_report - weight 5.0, then re-analyze after commit. */
    public ChefReport confirmReport(CustomUser admin, UUID reportUid) {
        ChefReport report = commands.confirm(admin, reportUid);
        analyzeAndAct(report.getChefId(), commands.dishOf(report));
        return report;
    }

    /** Django: lift_suspension (+ on_commit lift email). */
    public ChefSuspension liftSuspension(CustomUser admin, UUID suspensionUid, String liftNote) {
        SuspensionOutcome outcome = commands.lift(admin, suspensionUid, liftNote);
        emails.sendLifted(outcome.suspension().getChefId(), outcome.suspension(), outcome.dishName());
        return outcome.suspension();
    }

    public ChefSuspension rejectAppeal(CustomUser admin, UUID suspensionUid, String liftNote) {
        return commands.rejectAppeal(admin, suspensionUid, liftNote);
    }

    /** Django: manual_suspension (+ on_commit full-lock / dish-lock email). */
    public ChefSuspension manualSuspension(CustomUser admin, ManualSuspensionRequest request) {
        ManualSuspensionRequest.Validated v = request.validated();
        SuspensionOutcome outcome = commands.manualSuspension(admin, v.chefId(), v.type(), v.dishUid(), v.reason());
        if (v.type() == SuspensionType.FULL_LOCK) {
            emails.sendFullLock(v.chefId(), outcome.suspension());
        } else {
            emails.sendDishLock(v.chefId(), outcome.dishName(), outcome.suspension());
        }
        return outcome.suspension();
    }

    // ------------------------------------------------------------------------------------------------------------

    private static PageRequest pageRequest(int page, int pageSize) {
        int size = Math.max(1, pageSize);
        int zeroBased = Math.max(0, page - 1);
        return PageRequest.of(zeroBased, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    /** Django Paginator semantics: empty-first-page counts as 1 page; a page past the end returns no rows. */
    private static <S, T> PageResponse<T> page(List<S> rows, int page, int pageSize, long total, Function<S, T> map) {
        int size = Math.max(1, pageSize);
        int totalPages = (int) Math.max(1, Math.ceil((double) total / size));
        List<T> content = page > totalPages ? List.of() : rows.stream().map(map).toList();
        return new PageResponse<>(content, page, pageSize, total, totalPages);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
