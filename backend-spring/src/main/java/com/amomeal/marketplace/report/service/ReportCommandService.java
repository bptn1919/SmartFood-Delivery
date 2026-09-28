package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ChefSuspension;
import com.amomeal.marketplace.report.entity.ChefWarning;
import com.amomeal.marketplace.report.entity.ReportCategory;
import com.amomeal.marketplace.report.entity.ReportStatus;
import com.amomeal.marketplace.report.entity.SuspensionStatus;
import com.amomeal.marketplace.report.entity.SuspensionTrigger;
import com.amomeal.marketplace.report.entity.SuspensionType;
import com.amomeal.marketplace.report.entity.WarningType;
import com.amomeal.marketplace.report.exception.AppealAlreadySubmittedException;
import com.amomeal.marketplace.report.exception.AppealNotAllowedException;
import com.amomeal.marketplace.report.exception.ReportAlreadyExistsException;
import com.amomeal.marketplace.report.exception.ReportEvidenceRequiredException;
import com.amomeal.marketplace.report.exception.ReportNotFoundException;
import com.amomeal.marketplace.report.exception.ReportOrderNotCompletedException;
import com.amomeal.marketplace.report.exception.ReportOrderNotOwnedException;
import com.amomeal.marketplace.report.exception.ReportRateLimitExceededException;
import com.amomeal.marketplace.report.exception.ReportTargetNotFoundException;
import com.amomeal.marketplace.report.exception.ReportTargetRequiredException;
import com.amomeal.marketplace.report.exception.SuspensionNotFoundException;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ChefSuspensionRepository;
import com.amomeal.marketplace.report.repository.ChefWarningRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional (DB-writing) half of ../../backend/report/services/report_service.py. Every public method
 * is its own transaction (CLAUDE.md 8b) so {@link ReportService} - which is deliberately NOT transactional - can
 * reproduce Django's structure: {@code create_report} commits BEFORE Gemini / analysis / emails run, and each of
 * {@code _apply_full_lock}/{@code _apply_dish_lock}/{@code lift}/... is its own {@code @transaction.atomic} block
 * whose {@code on_commit} email runs after this method returns.
 */
@Slf4j
@Service
public class ReportCommandService {

    /** Result of {@link #create}: the saved report, the resolved chef id and (optional) dish. */
    public record CreatedReport(ChefReport report, Long chefId, Dish dish) {
    }

    /** A suspension plus the locked dish's name (read inside the transaction for the email). */
    public record SuspensionOutcome(ChefSuspension suspension, String dishName) {
    }

    private final ChefReportRepository reportRepository;
    private final ChefSuspensionRepository suspensionRepository;
    private final ChefWarningRepository warningRepository;
    private final OrderRepository orderRepository;
    private final DishRepository dishRepository;
    private final AttachmentRepository attachmentRepository;
    private final CustomUserRepository userRepository;
    private final ChefProfileRepository chefProfileRepository;
    private final ReportAnalysisService analysis;

    public ReportCommandService(ChefReportRepository reportRepository, ChefSuspensionRepository suspensionRepository,
                                ChefWarningRepository warningRepository, OrderRepository orderRepository,
                                DishRepository dishRepository, AttachmentRepository attachmentRepository,
                                CustomUserRepository userRepository, ChefProfileRepository chefProfileRepository,
                                ReportAnalysisService analysis) {
        this.reportRepository = reportRepository;
        this.suspensionRepository = suspensionRepository;
        this.warningRepository = warningRepository;
        this.orderRepository = orderRepository;
        this.dishRepository = dishRepository;
        this.attachmentRepository = attachmentRepository;
        this.userRepository = userRepository;
        this.chefProfileRepository = chefProfileRepository;
        this.analysis = analysis;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Customer: create
    // ------------------------------------------------------------------------------------------------------------

    /** Django: ReportService.create_report (validation order preserved exactly). */
    @Transactional
    public CreatedReport create(CustomUser reporter, UUID orderUid, UUID dishUid, Long chefId, ReportCategory category,
                                String description, UUID evidenceUid) {
        // 1. Rate limit (max 5 / 24h)
        if (!analysis.checkCustomerRateLimit(reporter.getId())) {
            throw new ReportRateLimitExceededException();
        }

        Order order = null;
        CustomUser chef = null;
        boolean orderRequired = ReportAnalysisService.ORDER_REQUIRED_CATEGORIES.contains(category);

        // 2. Order
        if (orderUid != null) {
            order = orderRepository.findDetailedByUid(orderUid).orElseThrow(ReportTargetNotFoundException::new);
            chef = order.getChef();
            if (orderRequired) {
                // PORT-NOTE: like Django, ownership/completion are only enforced for order-required categories.
                if (order.getOwner() == null || !Objects.equals(order.getOwner().getId(), reporter.getId())) {
                    throw new ReportOrderNotOwnedException();
                }
                if (order.getStatus() != OrderStatus.COMPLETED) {
                    throw new ReportOrderNotCompletedException();
                }
            }
        } else if (orderRequired) {
            throw new ReportTargetRequiredException();
        }

        // 3. Dish (no deleted filter, as Django's Dish.objects.get)
        Dish dish = null;
        if (dishUid != null) {
            dish = dishRepository.findByUid(dishUid).orElse(null);
            if (dish == null && !orderRequired) {
                throw new ReportTargetNotFoundException();
            }
        }

        // 4. Chef
        if (chef == null && dish != null) {
            chef = dish.getOwner();
        }
        if (chefId != null) {
            chef = userRepository.findById(chefId).orElseThrow(ReportTargetNotFoundException::new);
        }
        if (dish != null && chef != null && dish.getOwner() != null
                && !Objects.equals(dish.getOwner().getId(), chef.getId())) {
            throw new ReportTargetNotFoundException();
        }
        if (chef == null) {
            throw new ReportTargetRequiredException();
        }

        // 5. Duplicate (reporter + order + dish; null = IS NULL, so platform reports without order/dish are unique per reporter)
        if (reportRepository.existsByReporterAndOrderAndDish(reporter, order, dish)) {
            throw new ReportAlreadyExistsException();
        }

        // 6. Evidence: a missing attachment is silently ignored (Django `except DoesNotExist: pass`), then required-check
        Attachment evidence = evidenceUid == null ? null : attachmentRepository.findById(evidenceUid).orElse(null);
        if (ReportAnalysisService.PLATFORM_EVIDENCE_REQUIRED.contains(category) && evidence == null) {
            throw new ReportEvidenceRequiredException();
        }

        // 7. Initial credibility weight
        double weight = analysis.computeInitialWeight(evidence != null, description, reporter.getId());

        // 8. Save
        ChefReport report = reportRepository.save(ChefReport.builder()
                .reporter(reporter).reporterId(reporter.getId())
                .chef(chef).chefId(chef.getId())
                .order(order).orderUid(order == null ? null : order.getUid())
                .dish(dish).dishUid(dish == null ? null : dish.getUid())
                .evidence(evidence)
                .category(category)
                .description(description)
                .credibilityWeight(weight)
                .status(ReportStatus.PENDING)
                .build());
        return new CreatedReport(report, chef.getId(), dish);
    }

    /** Django: ChefReport.objects.filter(pk).update(ai_* , credibility_weight) after Gemini. */
    @Transactional
    public void applyAiResult(Long reportId, String severity, boolean foodSafetyRisk, String reason, double newWeight) {
        ChefReport report = reportRepository.findById(reportId).orElseThrow();
        report.setAiSeverity(severity);
        report.setAiFoodSafetyRisk(foodSafetyRisk);
        report.setAiSeverityReason(reason);
        report.setAiAnalyzedAt(Instant.now());
        report.setCredibilityWeight(newWeight);
        reportRepository.saveAndFlush(report);
    }

    // ------------------------------------------------------------------------------------------------------------
    // System: lock / warn
    // ------------------------------------------------------------------------------------------------------------

    /** Django: _apply_full_lock. */
    @Transactional
    public ChefSuspension applyFullLock(Long chefId, String reason, Map<String, Object> triggerData) {
        // Close every old ACTIVE DISH_LOCK first.
        // Post-port (2026-09-25): Django's bulk .update() left Dish.is_suspended=true on those dishes; now that
        // suspensions are enforced that would hide them forever (their suspension is LIFTED), so clear the flag.
        List<ChefSuspension> oldLocks = suspensionRepository
                .findByChefIdAndStatusAndSuspensionType(chefId, SuspensionStatus.ACTIVE, SuspensionType.DISH_LOCK);
        Instant now = Instant.now();
        for (ChefSuspension old : oldLocks) {
            old.setStatus(SuspensionStatus.LIFTED);
            old.setLiftNote("Được đóng tự động khi FULL_LOCK được áp dụng.");
            old.setLiftedAt(now);
            if (old.getLockedDishUid() != null) {
                setDishSuspended(old.getLockedDishUid(), false);
            }
        }
        suspensionRepository.saveAll(oldLocks);

        ChefSuspension suspension = suspensionRepository.save(ChefSuspension.builder()
                .chef(userRepository.getReferenceById(chefId)).chefId(chefId)
                .suspensionType(SuspensionType.FULL_LOCK)
                .reason(reason)
                .triggerSource(SuspensionTrigger.SYSTEM)
                .triggerData(triggerData == null ? new LinkedHashMap<>() : triggerData)
                .status(SuspensionStatus.ACTIVE)
                .build());
        setChefAcceptingOrders(chefId, false, ChefSuspensionLevel.SUSPENDED);
        log.info("FULL_LOCK applied to chef {} (suspension {})", chefId, suspension.getUid());
        return suspension;
    }

    /** Django: _apply_dish_lock. */
    @Transactional
    public ChefSuspension applyDishLock(Long chefId, Dish dish, String reason, Map<String, Object> triggerData) {
        ChefSuspension suspension = suspensionRepository.save(ChefSuspension.builder()
                .chef(userRepository.getReferenceById(chefId)).chefId(chefId)
                .lockedDish(dish).lockedDishUid(dish.getUid())
                .suspensionType(SuspensionType.DISH_LOCK)
                .reason(reason)
                .triggerSource(SuspensionTrigger.SYSTEM)
                .triggerData(triggerData == null ? new LinkedHashMap<>() : triggerData)
                .status(SuspensionStatus.ACTIVE)
                .build());
        setDishSuspended(dish.getUid(), true);
        log.info("DISH_LOCK applied: chef {}, dish {} (suspension {})", chefId, dish.getUid(), suspension.getUid());
        return suspension;
    }

    /**
     * Django: _apply_warning's dedupe + create - no FOOD_QUALITY warning for the same chef+dish within 24h.
     * Empty = duplicate (nothing created).
     */
    @Transactional
    public Optional<ChefWarning> createFoodQualityWarning(Long chefId, Dish dish, Map<String, Object> metrics) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        if (warningRepository.existsByChefIdAndWarnedDishAndWarningTypeAndCreatedAtGreaterThanEqual(
                chefId, dish, WarningType.FOOD_QUALITY, cutoff)) {
            return Optional.empty();
        }
        return Optional.of(warningRepository.save(ChefWarning.builder()
                .chef(userRepository.getReferenceById(chefId))
                .warnedDish(dish)
                .warningType(WarningType.FOOD_QUALITY)
                .metricsSnapshot(metrics == null ? new LinkedHashMap<>() : metrics)
                .build()));
    }

    /** Django: _analyze_delivery_and_act's 24h dedupe + create. */
    @Transactional
    public Optional<ChefWarning> createDeliveryWarning(Long chefId, Map<String, Object> metrics) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        if (warningRepository.existsByChefIdAndWarningTypeAndCreatedAtGreaterThanEqual(
                chefId, WarningType.DELIVERY, cutoff)) {
            return Optional.empty();
        }
        return Optional.of(warningRepository.save(ChefWarning.builder()
                .chef(userRepository.getReferenceById(chefId))
                .warningType(WarningType.DELIVERY)
                .metricsSnapshot(metrics == null ? new LinkedHashMap<>() : metrics)
                .build()));
    }

    /** Django: _handle_financial_report's create (no dedupe, no threshold). */
    @Transactional
    public ChefWarning createFinancialWarning(Long chefId, Map<String, Object> snapshot) {
        return warningRepository.save(ChefWarning.builder()
                .chef(userRepository.getReferenceById(chefId))
                .warningType(WarningType.FINANCIAL)
                .metricsSnapshot(snapshot)
                .build());
    }

    @Transactional
    public void markWarningEmailSent(Long warningId) {
        warningRepository.findById(warningId).ifPresent(w -> {
            w.setEmailSent(true);
            warningRepository.save(w);
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Chef: appeal
    // ------------------------------------------------------------------------------------------------------------

    /** Django: submit_appeal. Check order: not found -> status != ACTIVE -> already appealed. */
    @Transactional
    public ChefSuspension submitAppeal(CustomUser chef, UUID suspensionUid, String appealText) {
        ChefSuspension suspension = suspensionRepository.findForUpdateByUidAndChef(suspensionUid, chef.getId())
                .orElseThrow(SuspensionNotFoundException::new);
        if (suspension.getStatus() != SuspensionStatus.ACTIVE) {
            throw new AppealNotAllowedException();
        }
        // PORT-NOTE: unreachable through the normal flow (an appealed suspension is APPEALING, not ACTIVE) - as Django.
        if (suspension.getAppealedAt() != null) {
            throw new AppealAlreadySubmittedException();
        }
        suspension.setAppealText(appealText);
        suspension.setAppealedAt(Instant.now());
        suspension.setStatus(SuspensionStatus.APPEALING);
        return suspensionRepository.save(suspension);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------------------------------------------------

    /** Django: lift_suspension. */
    @Transactional
    public SuspensionOutcome lift(CustomUser admin, UUID suspensionUid, String liftNote) {
        ChefSuspension suspension = suspensionRepository.findForUpdateByUid(suspensionUid)
                .orElseThrow(SuspensionNotFoundException::new);
        // Post-port: a REJECTED appeal is no longer a dead end - admin may still lift it.
        if (suspension.getStatus() != SuspensionStatus.ACTIVE && suspension.getStatus() != SuspensionStatus.APPEALING
                && suspension.getStatus() != SuspensionStatus.REJECTED) {
            throw new AppealNotAllowedException();
        }
        suspension.setStatus(SuspensionStatus.LIFTED);
        suspension.setLiftedBy(admin);
        suspension.setLiftedAt(Instant.now());
        suspension.setLiftNote(liftNote);
        suspensionRepository.save(suspension);

        Long chefId = suspension.getChefId();
        String dishName = null;
        if (suspension.getSuspensionType() == SuspensionType.FULL_LOCK) {
            setChefAcceptingOrders(chefId, true, ChefSuspensionLevel.NONE);
        } else if (suspension.getSuspensionType() == SuspensionType.DISH_LOCK && suspension.getLockedDish() != null) {
            dishName = suspension.getLockedDish().getName();
            setDishSuspended(suspension.getLockedDish().getUid(), false);
        }
        return new SuspensionOutcome(suspension, dishName);
    }

    /**
     * Django: AdminReportController.reject_appeal (logic lives in the controller there).
     * PORT-NOTE: the docstring says "keep ACTIVE" but the code sets REJECTED, and lift only accepts ACTIVE/APPEALING -
     * Django: a rejected suspension could never be lifted; post-port {@link #lift} now accepts REJECTED too.
     */
    @Transactional
    public ChefSuspension rejectAppeal(CustomUser admin, UUID suspensionUid, String liftNote) {
        ChefSuspension suspension = suspensionRepository.findByUid(suspensionUid)
                .orElseThrow(SuspensionNotFoundException::new);
        if (suspension.getStatus() != SuspensionStatus.APPEALING) {
            throw new AppealNotAllowedException();
        }
        suspension.setStatus(SuspensionStatus.REJECTED);
        suspension.setLiftNote(liftNote);
        suspension.setLiftedBy(admin);
        suspension.setLiftedAt(Instant.now());
        return suspensionRepository.save(suspension);
    }

    /** Django: manual_suspension (bypasses thresholds). */
    @Transactional
    public SuspensionOutcome manualSuspension(CustomUser admin, Long chefId, SuspensionType type, UUID dishUid, String reason) {
        Dish dish = null;
        if (dishUid != null && type == SuspensionType.DISH_LOCK) {
            // PORT-NOTE: Django's Dish.objects.get is uncaught -> unknown dish = 500.
            dish = dishRepository.findByUid(dishUid).orElseThrow();
        }
        // PORT-NOTE: an unknown chef_id is an IntegrityError (500) in Django; getReference + flush reproduces the 500.
        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("admin_id", admin.getId());
        ChefSuspension suspension = suspensionRepository.saveAndFlush(ChefSuspension.builder()
                .chef(userRepository.getReferenceById(chefId)).chefId(chefId)
                .suspensionType(type)
                .lockedDish(dish).lockedDishUid(dish == null ? null : dish.getUid())
                .reason(reason)
                .triggerSource(SuspensionTrigger.ADMIN)
                .triggerData(triggerData)
                .status(SuspensionStatus.ACTIVE)
                .build());
        if (type == SuspensionType.FULL_LOCK) {
            setChefAcceptingOrders(chefId, false, ChefSuspensionLevel.SUSPENDED);
        } else if (dish != null) {
            setDishSuspended(dish.getUid(), true);
        }
        log.info("Manual {} applied to chef {} by admin {}", type, chefId, admin.getId());
        return new SuspensionOutcome(suspension, dish == null ? null : dish.getName());
    }

    /** Django: dismiss_report. */
    @Transactional
    public ChefReport dismiss(CustomUser admin, UUID reportUid, String adminNote) {
        ChefReport report = reportRepository.findByUid(reportUid).orElseThrow(ReportNotFoundException::new);
        report.setStatus(ReportStatus.DISMISSED);
        report.setAdminNote(adminNote);
        report.setReviewedBy(admin);
        report.setReviewedAt(Instant.now());
        return reportRepository.save(report);
    }

    /** Django: admin_confirm_report (weight = 5.0). The re-analysis runs after commit, in {@link ReportService}. */
    @Transactional
    public ChefReport confirm(CustomUser admin, UUID reportUid) {
        ChefReport report = reportRepository.findByUid(reportUid).orElseThrow(ReportNotFoundException::new);
        report.setCredibilityWeight(ReportAnalysisService.WEIGHT_ADMIN);
        report.setStatus(ReportStatus.REVIEWED);
        report.setReviewedBy(admin);
        report.setReviewedAt(Instant.now());
        return reportRepository.save(report);
    }

    /** The reported dish of a report (Django {@code report.dish}, needed for the post-confirm re-analysis). */
    @Transactional(readOnly = true)
    public Dish dishOf(ChefReport report) {
        return report.getDishUid() == null ? null : dishRepository.findByUid(report.getDishUid()).orElse(null);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Django: ChefProfile.objects.filter(user_id=).update(...) - a no-op when the chef has no profile. */
    private void setChefAcceptingOrders(Long chefId, boolean accepting, ChefSuspensionLevel level) {
        chefProfileRepository.findByUserId(chefId).ifPresent(profile -> {
            profile.setAcceptingOrders(accepting);
            profile.setSuspensionLevel(level);
            chefProfileRepository.save(profile);
        });
    }

    /** Django: Dish.objects.filter(uid=).update(is_suspended=...) - a no-op when the dish is gone. */
    private void setDishSuspended(UUID dishUid, boolean suspended) {
        dishRepository.findByUid(dishUid).ifPresent(d -> {
            d.setSuspended(suspended);
            dishRepository.save(d);
        });
    }
}
