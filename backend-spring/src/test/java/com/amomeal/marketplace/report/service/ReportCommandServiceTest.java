package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.dish.repository.DishRepository;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.order.repository.OrderRepository;
import com.amomeal.marketplace.profile.entity.ChefProfile;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every rule/branch of the persistence half of report_service.py (create_report, appeal, lift, manual, dismiss...). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportCommandServiceTest {

    @Mock ChefReportRepository reportRepository;
    @Mock ChefSuspensionRepository suspensionRepository;
    @Mock ChefWarningRepository warningRepository;
    @Mock OrderRepository orderRepository;
    @Mock DishRepository dishRepository;
    @Mock AttachmentRepository attachmentRepository;
    @Mock CustomUserRepository userRepository;
    @Mock ChefProfileRepository chefProfileRepository;
    @Mock ReportAnalysisService analysis;

    ReportCommandService service;
    CustomUser reporter;
    CustomUser chef;
    CustomUser admin;

    @BeforeEach
    void setUp() {
        service = new ReportCommandService(reportRepository, suspensionRepository, warningRepository, orderRepository,
                dishRepository, attachmentRepository, userRepository, chefProfileRepository, analysis);
        reporter = user(1L);
        chef = user(2L);
        admin = user(3L);
        when(analysis.checkCustomerRateLimit(anyLong())).thenReturn(true);
        when(analysis.computeInitialWeight(any(Boolean.class), any(), any())).thenReturn(1.5);
        when(reportRepository.save(any(ChefReport.class))).thenAnswer(i -> i.getArgument(0));
        when(suspensionRepository.save(any(ChefSuspension.class))).thenAnswer(i -> i.getArgument(0));
        when(suspensionRepository.saveAndFlush(any(ChefSuspension.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.getReferenceById(anyLong())).thenAnswer(i -> user(i.getArgument(0)));
    }

    private static CustomUser user(Long id) {
        CustomUser u = new CustomUser();
        u.setId(id);
        return u;
    }

    private Order order(OrderStatus status, CustomUser owner) {
        return Order.builder().uid(UUID.randomUUID()).owner(owner).chef(chef).status(status).build();
    }

    private Dish dish(CustomUser owner) {
        return Dish.builder().uid(UUID.randomUUID()).name("Phở").owner(owner).build();
    }

    private void givenOrder(Order o) {
        when(orderRepository.findDetailedByUid(o.getUid())).thenReturn(Optional.of(o));
    }

    private ChefReport create(UUID orderUid, UUID dishUid, Long chefId, ReportCategory cat, UUID evidenceUid) {
        return service.create(reporter, orderUid, dishUid, chefId, cat, "mô tả đủ dài để hợp lệ", evidenceUid).report();
    }

    // ==== create_report =============================================================================================

    @Test
    void create_rateLimitExceeded_isFirstCheck_429() {
        when(analysis.checkCustomerRateLimit(1L)).thenReturn(false);

        assertThatThrownBy(() -> create(UUID.randomUUID(), null, null, ReportCategory.FOOD_SAFETY, null))
                .isInstanceOf(ReportRateLimitExceededException.class);
        verify(orderRepository, never()).findDetailedByUid(any());
    }

    @Test
    void create_orderNotFound_isTargetNotFound() {
        assertThatThrownBy(() -> create(UUID.randomUUID(), null, null, ReportCategory.FOOD_SAFETY, null))
                .isInstanceOf(ReportTargetNotFoundException.class);
    }

    @Test
    void create_orderOwnedBySomeoneElse_isNotOwned() {
        Order o = order(OrderStatus.COMPLETED, user(99L));
        givenOrder(o);
        assertThatThrownBy(() -> create(o.getUid(), null, null, ReportCategory.FOOD_QUALITY, null))
                .isInstanceOf(ReportOrderNotOwnedException.class);
    }

    @Test
    void create_orderWithNullOwner_isNotOwned() {
        Order o = order(OrderStatus.COMPLETED, null);
        givenOrder(o);
        assertThatThrownBy(() -> create(o.getUid(), null, null, ReportCategory.HYGIENE, null))
                .isInstanceOf(ReportOrderNotOwnedException.class);
    }

    @Test
    void create_orderNotCompleted_isNotCompleted_afterTheOwnershipCheck() {
        Order o = order(OrderStatus.DELIVERING, reporter);
        givenOrder(o);
        assertThatThrownBy(() -> create(o.getUid(), null, null, ReportCategory.WRONG_ITEM, null))
                .isInstanceOf(ReportOrderNotCompletedException.class);

        // ownership is checked BEFORE completion
        Order foreign = order(OrderStatus.DELIVERING, user(99L));
        givenOrder(foreign);
        assertThatThrownBy(() -> create(foreign.getUid(), null, null, ReportCategory.WRONG_ITEM, null))
                .isInstanceOf(ReportOrderNotOwnedException.class);
    }

    @Test
    void create_platformCategoryWithAnOrder_skipsOwnershipAndCompletionChecks() {
        // PORT-NOTE: Django only enforces owner/COMPLETED for order-required categories.
        Order o = order(OrderStatus.PENDING, user(99L));
        givenOrder(o);
        Attachment evidence = Attachment.builder().uid(UUID.randomUUID()).build();
        when(attachmentRepository.findById(evidence.getUid())).thenReturn(Optional.of(evidence));

        ChefReport r = create(o.getUid(), null, null, ReportCategory.FRAUD, evidence.getUid());

        assertThat(r.getChefId()).isEqualTo(2L);
        assertThat(r.getOrderUid()).isEqualTo(o.getUid());
    }

    @Test
    void create_orderRequiredCategoryWithoutOrder_isTargetRequired() {
        assertThatThrownBy(() -> create(null, null, null, ReportCategory.PAYMENT_ISSUE, null))
                .isInstanceOf(ReportTargetRequiredException.class);
    }

    @Test
    void create_platformCategory_unknownDish_isTargetNotFound() {
        assertThatThrownBy(() -> create(null, UUID.randomUUID(), null, ReportCategory.INAPPROPRIATE, null))
                .isInstanceOf(ReportTargetNotFoundException.class);
    }

    @Test
    void create_orderRequiredCategory_unknownDish_isIgnored() {
        Order o = order(OrderStatus.COMPLETED, reporter);
        givenOrder(o);

        ChefReport r = create(o.getUid(), UUID.randomUUID(), null, ReportCategory.FOOD_SAFETY, null);

        assertThat(r.getDishUid()).isNull();
        assertThat(r.getChefId()).isEqualTo(2L);
    }

    @Test
    void create_chefIdOverridesTheOrdersChef_andUnknownChefIsTargetNotFound() {
        Order o = order(OrderStatus.COMPLETED, reporter);
        givenOrder(o);
        when(userRepository.findById(50L)).thenReturn(Optional.of(user(50L)));

        assertThat(create(o.getUid(), null, 50L, ReportCategory.FOOD_SAFETY, null).getChefId()).isEqualTo(50L);

        when(userRepository.findById(51L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> create(o.getUid(), null, 51L, ReportCategory.FOOD_SAFETY, null))
                .isInstanceOf(ReportTargetNotFoundException.class);
    }

    @Test
    void create_dishOwnedByAnotherChef_isTargetNotFound() {
        Order o = order(OrderStatus.COMPLETED, reporter);
        givenOrder(o);
        Dish d = dish(user(77L));
        when(dishRepository.findByUid(d.getUid())).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> create(o.getUid(), d.getUid(), null, ReportCategory.FOOD_SAFETY, null))
                .isInstanceOf(ReportTargetNotFoundException.class);
    }

    @Test
    void create_chefTakenFromTheDishOwner_whenNoOrder() {
        Dish d = dish(chef);
        when(dishRepository.findByUid(d.getUid())).thenReturn(Optional.of(d));
        Attachment evidence = Attachment.builder().uid(UUID.randomUUID()).build();
        when(attachmentRepository.findById(evidence.getUid())).thenReturn(Optional.of(evidence));

        ChefReport r = create(null, d.getUid(), null, ReportCategory.INAPPROPRIATE, evidence.getUid());

        assertThat(r.getChefId()).isEqualTo(2L);
        assertThat(r.getDishUid()).isEqualTo(d.getUid());
    }

    @Test
    void create_noChefResolvable_isTargetRequired() {
        assertThatThrownBy(() -> create(null, null, null, ReportCategory.INAPPROPRIATE, null))
                .isInstanceOf(ReportTargetRequiredException.class);
    }

    @Test
    void create_duplicateReporterOrderDish_is409() {
        Order o = order(OrderStatus.COMPLETED, reporter);
        givenOrder(o);
        when(reportRepository.existsByReporterAndOrderAndDish(reporter, o, null)).thenReturn(true);

        assertThatThrownBy(() -> create(o.getUid(), null, null, ReportCategory.FOOD_SAFETY, null))
                .isInstanceOf(ReportAlreadyExistsException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void create_platformCategory_requiresEvidence_missingOrUnknownAttachment() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(chef));

        assertThatThrownBy(() -> create(null, null, 2L, ReportCategory.FRAUD, null))
                .isInstanceOf(ReportEvidenceRequiredException.class);
        // an evidence uid that does not resolve is silently dropped (Django `except DoesNotExist: pass`) -> still required
        assertThatThrownBy(() -> create(null, null, 2L, ReportCategory.FRAUD, UUID.randomUUID()))
                .isInstanceOf(ReportEvidenceRequiredException.class);
    }

    @Test
    void create_orderRequiredCategory_doesNotNeedEvidence_andUnknownEvidenceIsIgnored() {
        Order o = order(OrderStatus.COMPLETED, reporter);
        givenOrder(o);

        ChefReport r = create(o.getUid(), null, null, ReportCategory.FOOD_QUALITY, UUID.randomUUID());

        assertThat(r.getEvidence()).isNull();
    }

    @Test
    void create_happyPath_savesPendingReportWithComputedWeight() {
        Order o = order(OrderStatus.COMPLETED, reporter);
        givenOrder(o);
        Dish d = dish(chef);
        when(dishRepository.findByUid(d.getUid())).thenReturn(Optional.of(d));
        Attachment evidence = Attachment.builder().uid(UUID.randomUUID()).build();
        when(attachmentRepository.findById(evidence.getUid())).thenReturn(Optional.of(evidence));
        when(analysis.computeInitialWeight(true, "mô tả đủ dài để hợp lệ", 1L)).thenReturn(2.0);

        ReportCommandService.CreatedReport created = service.create(reporter, o.getUid(), d.getUid(), null,
                ReportCategory.FOOD_SAFETY, "mô tả đủ dài để hợp lệ", evidence.getUid());

        ChefReport r = created.report();
        assertThat(r.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(r.getCredibilityWeight()).isEqualTo(2.0);
        assertThat(r.getCategory()).isEqualTo(ReportCategory.FOOD_SAFETY);
        assertThat(r.getReporterId()).isEqualTo(1L);
        assertThat(r.getChefId()).isEqualTo(2L);
        assertThat(r.getOrderUid()).isEqualTo(o.getUid());
        assertThat(r.getDishUid()).isEqualTo(d.getUid());
        assertThat(r.getEvidence()).isSameAs(evidence);
        assertThat(created.chefId()).isEqualTo(2L);
        assertThat(created.dish()).isSameAs(d);
    }

    // ==== applyAiResult ==============================================================================================

    @Test
    void applyAiResult_setsAllAiFieldsAndWeight() {
        ChefReport r = ChefReport.builder().id(9L).build();
        when(reportRepository.findById(9L)).thenReturn(Optional.of(r));

        service.applyAiResult(9L, "HIGH", true, "vì sao", 3.0);

        assertThat(r.getAiSeverity()).isEqualTo("HIGH");
        assertThat(r.getAiFoodSafetyRisk()).isTrue();
        assertThat(r.getAiSeverityReason()).isEqualTo("vì sao");
        assertThat(r.getAiAnalyzedAt()).isNotNull();
        assertThat(r.getCredibilityWeight()).isEqualTo(3.0);
    }

    // ==== locks ======================================================================================================

    @Test
    void applyFullLock_closesOldDishLocks_createsFullLock_andSuspendsTheChefProfile() {
        ChefSuspension oldLock = ChefSuspension.builder().suspensionType(SuspensionType.DISH_LOCK)
                .status(SuspensionStatus.ACTIVE).build();
        when(suspensionRepository.findByChefIdAndStatusAndSuspensionType(2L, SuspensionStatus.ACTIVE, SuspensionType.DISH_LOCK))
                .thenReturn(List.of(oldLock));
        ChefProfile profile = ChefProfile.builder().build();
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        ChefSuspension s = service.applyFullLock(2L, "lý do", java.util.Map.of("chef_ratio", 0.1));

        assertThat(oldLock.getStatus()).isEqualTo(SuspensionStatus.LIFTED);
        assertThat(oldLock.getLiftNote()).isEqualTo("Được đóng tự động khi FULL_LOCK được áp dụng.");
        assertThat(oldLock.getLiftedAt()).isNotNull();
        assertThat(s.getSuspensionType()).isEqualTo(SuspensionType.FULL_LOCK);
        assertThat(s.getTriggerSource()).isEqualTo(SuspensionTrigger.SYSTEM);
        assertThat(s.getStatus()).isEqualTo(SuspensionStatus.ACTIVE);
        assertThat(s.getTriggerData()).containsEntry("chef_ratio", 0.1);
        assertThat(profile.isAcceptingOrders()).isFalse();
        assertThat(profile.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.SUSPENDED);
    }

    @Test
    void applyFullLock_chefWithoutProfile_isANoOpOnTheProfile() {
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.empty());
        ChefSuspension s = service.applyFullLock(2L, "r", null);
        assertThat(s.getTriggerData()).isEmpty();
        verify(chefProfileRepository, never()).save(any());
    }

    @Test
    void applyDishLock_createsSuspensionAndFlagsTheDish() {
        Dish d = dish(chef);
        when(dishRepository.findByUid(d.getUid())).thenReturn(Optional.of(d));

        ChefSuspension s = service.applyDishLock(2L, d, "r", java.util.Map.of());

        assertThat(s.getSuspensionType()).isEqualTo(SuspensionType.DISH_LOCK);
        assertThat(s.getLockedDishUid()).isEqualTo(d.getUid());
        assertThat(d.isSuspended()).isTrue();
    }

    @Test
    void foodQualityWarning_isDedupedWithin24hForTheSameChefAndDish() {
        Dish d = dish(chef);
        when(warningRepository.existsByChefIdAndWarnedDishAndWarningTypeAndCreatedAtGreaterThanEqual(
                eq(2L), eq(d), eq(WarningType.FOOD_QUALITY), any())).thenReturn(true);

        assertThat(service.createFoodQualityWarning(2L, d, java.util.Map.of())).isEmpty();
        verify(warningRepository, never()).save(any());
    }

    @Test
    void foodQualityWarning_createdWhenNoRecentOne() {
        when(warningRepository.save(any(ChefWarning.class))).thenAnswer(i -> i.getArgument(0));

        ChefWarning w = service.createFoodQualityWarning(2L, null, java.util.Map.of("x", 1)).orElseThrow();

        assertThat(w.getWarningType()).isEqualTo(WarningType.FOOD_QUALITY);
        assertThat(w.getMetricsSnapshot()).containsEntry("x", 1);
        assertThat(w.isEmailSent()).isFalse();
    }

    @Test
    void deliveryWarning_isDedupedWithin24h() {
        when(warningRepository.existsByChefIdAndWarningTypeAndCreatedAtGreaterThanEqual(eq(2L), eq(WarningType.DELIVERY), any()))
                .thenReturn(true);
        assertThat(service.createDeliveryWarning(2L, java.util.Map.of())).isEmpty();
    }

    // ==== appeal ======================================================================================================

    private ChefSuspension suspension(SuspensionStatus status, SuspensionType type) {
        return ChefSuspension.builder().uid(UUID.randomUUID()).chefId(2L).suspensionType(type).status(status).build();
    }

    @Test
    void appeal_unknownOrForeignSuspension_isNotFound() {
        when(suspensionRepository.findForUpdateByUidAndChef(any(), eq(2L))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submitAppeal(chef, UUID.randomUUID(), "x".repeat(20)))
                .isInstanceOf(SuspensionNotFoundException.class);
    }

    @Test
    void appeal_onlyAllowedWhileActive() {
        for (SuspensionStatus s : List.of(SuspensionStatus.APPEALING, SuspensionStatus.LIFTED, SuspensionStatus.REJECTED)) {
            ChefSuspension susp = suspension(s, SuspensionType.FULL_LOCK);
            when(suspensionRepository.findForUpdateByUidAndChef(susp.getUid(), 2L)).thenReturn(Optional.of(susp));
            assertThatThrownBy(() -> service.submitAppeal(chef, susp.getUid(), "x".repeat(20)))
                    .isInstanceOf(AppealNotAllowedException.class);
        }
    }

    @Test
    void appeal_activeButAlreadyAppealed_isAlreadySubmitted_theOnlyReachablePath() {
        ChefSuspension susp = suspension(SuspensionStatus.ACTIVE, SuspensionType.FULL_LOCK);
        susp.setAppealedAt(java.time.Instant.now());
        when(suspensionRepository.findForUpdateByUidAndChef(susp.getUid(), 2L)).thenReturn(Optional.of(susp));

        assertThatThrownBy(() -> service.submitAppeal(chef, susp.getUid(), "x".repeat(20)))
                .isInstanceOf(AppealAlreadySubmittedException.class);
    }

    @Test
    void appeal_success_movesToAppealing() {
        ChefSuspension susp = suspension(SuspensionStatus.ACTIVE, SuspensionType.FULL_LOCK);
        when(suspensionRepository.findForUpdateByUidAndChef(susp.getUid(), 2L)).thenReturn(Optional.of(susp));

        ChefSuspension out = service.submitAppeal(chef, susp.getUid(), "giải trình đầy đủ");

        assertThat(out.getStatus()).isEqualTo(SuspensionStatus.APPEALING);
        assertThat(out.getAppealText()).isEqualTo("giải trình đầy đủ");
        assertThat(out.getAppealedAt()).isNotNull();
    }

    // ==== lift / reject / manual ======================================================================================

    @Test
    void lift_unknown_isNotFound() {
        when(suspensionRepository.findForUpdateByUid(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lift(admin, UUID.randomUUID(), "n")).isInstanceOf(SuspensionNotFoundException.class);
    }

    @Test
    void lift_alreadyLifted_isAppealNotAllowed() {
        ChefSuspension susp = suspension(SuspensionStatus.LIFTED, SuspensionType.FULL_LOCK);
        when(suspensionRepository.findForUpdateByUid(susp.getUid())).thenReturn(Optional.of(susp));
        assertThatThrownBy(() -> service.lift(admin, susp.getUid(), "n")).isInstanceOf(AppealNotAllowedException.class);
    }

    @Test
    void lift_rejectedSuspension_isNoLongerADeadEnd_restoresTheChef() {
        // Post-port fix (2026-09-25): Django only accepted ACTIVE/APPEALING; REJECTED could never be lifted.
        ChefSuspension susp = suspension(SuspensionStatus.REJECTED, SuspensionType.FULL_LOCK);
        when(suspensionRepository.findForUpdateByUid(susp.getUid())).thenReturn(Optional.of(susp));
        ChefProfile profile = ChefProfile.builder().isAcceptingOrders(false).suspensionLevel(ChefSuspensionLevel.SUSPENDED).build();
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        service.lift(admin, susp.getUid(), "xem xét lại");

        assertThat(susp.getStatus()).isEqualTo(SuspensionStatus.LIFTED);
        assertThat(profile.isAcceptingOrders()).isTrue();
        assertThat(profile.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.NONE);
    }

    @Test
    void applyFullLock_clearsTheSuspendedFlagOfDishesWhoseDishLockItSupersedes() {
        Dish locked = dish(chef);
        locked.setSuspended(true);
        when(dishRepository.findByUid(locked.getUid())).thenReturn(Optional.of(locked));
        ChefSuspension oldLock = ChefSuspension.builder().suspensionType(SuspensionType.DISH_LOCK)
                .status(SuspensionStatus.ACTIVE).lockedDishUid(locked.getUid()).build();
        when(suspensionRepository.findByChefIdAndStatusAndSuspensionType(2L, SuspensionStatus.ACTIVE, SuspensionType.DISH_LOCK))
                .thenReturn(List.of(oldLock));

        service.applyFullLock(2L, "r", null);

        assertThat(locked.isSuspended()).isFalse();
    }

    @Test
    void lift_fullLock_fromAppealing_restoresTheChefProfile() {
        ChefSuspension susp = suspension(SuspensionStatus.APPEALING, SuspensionType.FULL_LOCK);
        when(suspensionRepository.findForUpdateByUid(susp.getUid())).thenReturn(Optional.of(susp));
        ChefProfile profile = ChefProfile.builder().isAcceptingOrders(false).suspensionLevel(ChefSuspensionLevel.SUSPENDED).build();
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        ReportCommandService.SuspensionOutcome out = service.lift(admin, susp.getUid(), "ok rồi");

        assertThat(out.suspension().getStatus()).isEqualTo(SuspensionStatus.LIFTED);
        assertThat(out.suspension().getLiftNote()).isEqualTo("ok rồi");
        assertThat(out.suspension().getLiftedBy()).isSameAs(admin);
        assertThat(out.suspension().getLiftedAt()).isNotNull();
        assertThat(profile.isAcceptingOrders()).isTrue();
        assertThat(profile.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.NONE);
    }

    @Test
    void lift_dishLock_unsuspendsTheDish_andReportsItsName() {
        Dish d = dish(chef);
        d.setSuspended(true);
        ChefSuspension susp = suspension(SuspensionStatus.ACTIVE, SuspensionType.DISH_LOCK);
        susp.setLockedDish(d);
        when(suspensionRepository.findForUpdateByUid(susp.getUid())).thenReturn(Optional.of(susp));
        when(dishRepository.findByUid(d.getUid())).thenReturn(Optional.of(d));

        ReportCommandService.SuspensionOutcome out = service.lift(admin, susp.getUid(), "");

        assertThat(d.isSuspended()).isFalse();
        assertThat(out.dishName()).isEqualTo("Phở");
    }

    @Test
    void rejectAppeal_unknown_notAppealing_andSuccess() {
        UUID unknown = UUID.randomUUID();
        when(suspensionRepository.findByUid(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rejectAppeal(admin, unknown, "n")).isInstanceOf(SuspensionNotFoundException.class);

        ChefSuspension active = suspension(SuspensionStatus.ACTIVE, SuspensionType.FULL_LOCK);
        when(suspensionRepository.findByUid(active.getUid())).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.rejectAppeal(admin, active.getUid(), "n")).isInstanceOf(AppealNotAllowedException.class);

        ChefSuspension appealing = suspension(SuspensionStatus.APPEALING, SuspensionType.FULL_LOCK);
        when(suspensionRepository.findByUid(appealing.getUid())).thenReturn(Optional.of(appealing));
        ChefSuspension out = service.rejectAppeal(admin, appealing.getUid(), "không đạt");
        // PORT-NOTE: REJECTED (not "keep ACTIVE" as the docstring says) - and it can never be lifted afterwards.
        assertThat(out.getStatus()).isEqualTo(SuspensionStatus.REJECTED);
        assertThat(out.getLiftNote()).isEqualTo("không đạt");
        assertThat(out.getLiftedBy()).isSameAs(admin);
    }

    @Test
    void manualSuspension_fullLock_suspendsTheProfile_withAdminTriggerData() {
        ChefProfile profile = ChefProfile.builder().build();
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        ReportCommandService.SuspensionOutcome out = service.manualSuspension(admin, 2L, SuspensionType.FULL_LOCK, null, "vi phạm");

        assertThat(out.suspension().getTriggerSource()).isEqualTo(SuspensionTrigger.ADMIN);
        assertThat(out.suspension().getTriggerData()).containsEntry("admin_id", 3L);
        assertThat(out.suspension().getReason()).isEqualTo("vi phạm");
        assertThat(profile.isAcceptingOrders()).isFalse();
        assertThat(profile.getSuspensionLevel()).isEqualTo(ChefSuspensionLevel.SUSPENDED);
    }

    @Test
    void manualSuspension_dishLock_flagsTheDish_andUnknownDishIsAnUncaught500() {
        Dish d = dish(chef);
        when(dishRepository.findByUid(d.getUid())).thenReturn(Optional.of(d));

        ReportCommandService.SuspensionOutcome out = service.manualSuspension(admin, 2L, SuspensionType.DISH_LOCK, d.getUid(), "r");

        assertThat(d.isSuspended()).isTrue();
        assertThat(out.suspension().getLockedDishUid()).isEqualTo(d.getUid());
        assertThat(out.dishName()).isEqualTo("Phở");

        // PORT-NOTE: Django's Dish.objects.get is uncaught -> not an ApiException -> 500
        assertThatThrownBy(() -> service.manualSuspension(admin, 2L, SuspensionType.DISH_LOCK, UUID.randomUUID(), "r"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ==== dismiss / confirm ==========================================================================================

    @Test
    void dismiss_unknown_isNotFound_elseMarksDismissedWithNote() {
        UUID unknown = UUID.randomUUID();
        when(reportRepository.findByUid(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dismiss(admin, unknown, "n")).isInstanceOf(ReportNotFoundException.class);

        ChefReport r = ChefReport.builder().uid(UUID.randomUUID()).build();
        when(reportRepository.findByUid(r.getUid())).thenReturn(Optional.of(r));
        ChefReport out = service.dismiss(admin, r.getUid(), "spam");
        assertThat(out.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(out.getAdminNote()).isEqualTo("spam");
        assertThat(out.getReviewedBy()).isSameAs(admin);
        assertThat(out.getReviewedAt()).isNotNull();
    }

    @Test
    void confirm_unknown_isNotFound_elseWeightFiveAndReviewed() {
        UUID unknown = UUID.randomUUID();
        when(reportRepository.findByUid(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(admin, unknown)).isInstanceOf(ReportNotFoundException.class);

        ChefReport r = ChefReport.builder().uid(UUID.randomUUID()).credibilityWeight(1.0).build();
        when(reportRepository.findByUid(r.getUid())).thenReturn(Optional.of(r));
        ArgumentCaptor<ChefReport> saved = ArgumentCaptor.forClass(ChefReport.class);
        service.confirm(admin, r.getUid());
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getCredibilityWeight()).isEqualTo(5.0);
        assertThat(saved.getValue().getStatus()).isEqualTo(ReportStatus.REVIEWED);
        assertThat(saved.getValue().getReviewedBy()).isSameAs(admin);
    }
}
