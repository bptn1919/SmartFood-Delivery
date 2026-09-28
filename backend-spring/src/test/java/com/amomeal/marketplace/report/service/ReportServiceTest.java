package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.dish.entity.Dish;
import com.amomeal.marketplace.profile.entity.ChefProfile;
import com.amomeal.marketplace.profile.entity.ChefSuspensionLevel;
import com.amomeal.marketplace.profile.repository.ChefProfileRepository;
import com.amomeal.marketplace.report.config.ReportProperties;
import com.amomeal.marketplace.report.dto.CreateReportRequest;
import com.amomeal.marketplace.report.dto.ChefSuspensionStatusResponse;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ChefSuspension;
import com.amomeal.marketplace.report.entity.ChefWarning;
import com.amomeal.marketplace.report.entity.ReportCategory;
import com.amomeal.marketplace.report.entity.SuspensionStatus;
import com.amomeal.marketplace.report.entity.SuspensionType;
import com.amomeal.marketplace.report.exception.ReportValidationException;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ChefSuspensionRepository;
import com.amomeal.marketplace.report.service.GeminiSeverityService.SeverityResult;
import com.amomeal.marketplace.users.entity.CustomUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestration half of report_service.py: post-create Gemini + routing, and the severity -> WARNING /
 * DISH_LOCK / FULL_LOCK escalation with its "never downgrade / no duplicates" guards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock ReportCommandService commands;
    @Mock ReportAnalysisService analysis;
    @Mock GeminiSeverityService gemini;
    @Mock ReportEmailService emails;
    @Mock ChefReportRepository reportRepository;
    @Mock ChefSuspensionRepository suspensionRepository;
    @Mock ChefProfileRepository chefProfileRepository;

    ReportProperties properties;
    ReportService service;
    Dish dish;

    @BeforeEach
    void setUp() {
        properties = new ReportProperties();
        service = new ReportService(commands, analysis, gemini, emails, new ReportMapper(properties), properties,
                reportRepository, suspensionRepository, chefProfileRepository);
        dish = Dish.builder().uid(UUID.randomUUID()).name("Phở").build();
        when(suspensionRepository.findFirstByChefIdAndStatusOrderByIdAsc(anyLong(), any())).thenReturn(Optional.empty());
    }

    private static Map<String, Object> metrics(double chefRatio, int chefCount, int unique, double dishRatio,
                                               int dishCount, int dishOrders) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chef_ratio", chefRatio);
        m.put("chef_report_count", chefCount);
        m.put("unique_reporters", unique);
        m.put("review_count", 0);
        m.put("dish_ratio", dishRatio);
        m.put("dish_report_count", dishCount);
        m.put("dish_orders", dishOrders);
        return m;
    }

    private ChefReport report(ReportCategory category) {
        return ChefReport.builder().id(11L).uid(UUID.randomUUID()).category(category).description("mô tả").chefId(2L)
                .credibilityWeight(1.5).build();
    }

    // ---- Gemini + routing --------------------------------------------------------------------------------

    @Test
    void foodQualityReport_callsGemini_raisesWeightWhenCritical_thenAnalyzes() {
        ChefReport r = report(ReportCategory.FOOD_SAFETY);
        when(gemini.analyze("FOOD_SAFETY", "mô tả"))
                .thenReturn(new SeverityResult("CRITICAL", true, List.of(), "high", "nguy hiểm"));
        when(analysis.computeChefMetrics(2L, null)).thenReturn(null);

        service.runPostCreate(r, 2L, null);

        verify(commands).applyAiResult(11L, "CRITICAL", true, "nguy hiểm", 3.0);
        assertThat(r.getCredibilityWeight()).isEqualTo(3.0);
        assertThat(r.getAiSeverity()).isEqualTo("CRITICAL");
        verify(analysis).computeChefMetrics(2L, null);
    }

    @Test
    void geminiFallbackLow_keepsWeight_andStillAnalyzes() {
        ChefReport r = report(ReportCategory.HYGIENE);
        when(gemini.analyze(anyString(), anyString())).thenReturn(GeminiSeverityService.fallback());

        service.runPostCreate(r, 2L, null);

        verify(commands).applyAiResult(11L, "LOW", false, "Không thể phân tích tự động.", 1.5);
        assertThat(r.getCredibilityWeight()).isEqualTo(1.5);
        verify(analysis).computeChefMetrics(2L, null);
    }

    @Test
    void persistingTheAiResultFails_isSwallowed_reportKept_analysisStillRuns() {
        ChefReport r = report(ReportCategory.FOOD_QUALITY);
        when(gemini.analyze(anyString(), anyString()))
                .thenReturn(new SeverityResult("EXTREMELY_BAD_SEVERITY", true, List.of(), "high", "x"));
        doThrow(new IllegalStateException("value too long")).when(commands)
                .applyAiResult(anyLong(), anyString(), anyBoolean(), anyString(), anyDouble());

        service.runPostCreate(r, 2L, null);

        assertThat(r.getCredibilityWeight()).isEqualTo(1.5); // unchanged
        verify(analysis).computeChefMetrics(2L, null);
    }

    @Test
    void deliveryReport_skipsGemini_routesToDeliveryAnalysis() {
        service.runPostCreate(report(ReportCategory.WRONG_ITEM), 2L, null);

        verify(gemini, never()).analyze(anyString(), anyString());
        verify(analysis).computeDeliveryMetrics(2L);
        verify(analysis, never()).computeChefMetrics(anyLong(), any());
    }

    @Test
    void platformReport_isRecordedOnly_noGemini_noAnalysis() {
        service.runPostCreate(report(ReportCategory.FRAUD), 2L, null);

        verify(gemini, never()).analyze(anyString(), anyString());
        verify(analysis, never()).computeChefMetrics(anyLong(), any());
        verify(analysis, never()).computeDeliveryMetrics(anyLong());
        verify(commands, never()).createFinancialWarning(anyLong(), any());
    }

    @Test
    void financialReport_writesAFinancialWarning_andAlertsEmails() {
        ChefReport r = report(ReportCategory.FINANCIAL);
        UUID orderUid = UUID.randomUUID();
        r.setOrderUid(orderUid);
        ChefWarning w = ChefWarning.builder().id(5L).build();
        when(commands.createFinancialWarning(eq(2L), any())).thenReturn(w);

        service.runPostCreate(r, 2L, null);

        ArgumentCaptor<Map<String, Object>> snap = ArgumentCaptor.forClass(Map.class);
        verify(commands).createFinancialWarning(eq(2L), snap.capture());
        assertThat(snap.getValue()).containsEntry("report_uid", r.getUid().toString())
                .containsEntry("description", "mô tả").containsEntry("order_id", orderUid.toString());
        verify(emails).sendFinancialAlerts(2L, r, orderUid.toString());
        verify(commands).markWarningEmailSent(5L);
    }

    @Test
    void financialReport_descriptionTruncatedTo200Chars() {
        ChefReport r = report(ReportCategory.PAYMENT_ISSUE);
        r.setDescription("a".repeat(300));
        when(commands.createFinancialWarning(anyLong(), any())).thenReturn(ChefWarning.builder().id(5L).build());

        service.runPostCreate(r, 2L, null);

        ArgumentCaptor<Map<String, Object>> snap = ArgumentCaptor.forClass(Map.class);
        verify(commands).createFinancialWarning(eq(2L), snap.capture());
        assertThat((String) snap.getValue().get("description")).hasSize(200);
        assertThat(snap.getValue().get("order_id")).isNull();
    }

    @Test
    void financialReportWithOrder_preserveFlag_reproducesDjangosTypeError() {
        properties.setPreserveOrderUuidBug(true);
        ChefReport r = report(ReportCategory.REFUND_ISSUE);
        r.setOrderUid(UUID.randomUUID());

        assertThatThrownBy(() -> service.runPostCreate(r, 2L, null)).isInstanceOf(IllegalStateException.class);
        verify(commands, never()).createFinancialWarning(anyLong(), any());
    }

    @Test
    void deliveryWarning_createdOnlyWhenDecided_andDedupedWhenAlreadySent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_ratio", 0.06);
        m.put("report_count", 1);
        when(analysis.computeDeliveryMetrics(2L)).thenReturn(m);
        when(commands.createDeliveryWarning(2L, m)).thenReturn(Optional.of(ChefWarning.builder().id(8L).build()));

        service.analyzeDeliveryAndAct(2L);

        verify(emails).sendDeliveryWarning(eq(2L), anyString(), eq(m), eq(false));
        verify(commands).markWarningEmailSent(8L);

        // duplicate within 24h -> nothing sent
        when(commands.createDeliveryWarning(2L, m)).thenReturn(Optional.empty());
        org.mockito.Mockito.clearInvocations(emails, commands);
        service.analyzeDeliveryAndAct(2L);
        verify(emails, never()).sendDeliveryWarning(anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    void deliveryAdminAlert_flagIsPassedToTheEmail() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_ratio", 0.2);
        m.put("report_count", 5);
        when(analysis.computeDeliveryMetrics(2L)).thenReturn(m);
        when(commands.createDeliveryWarning(2L, m)).thenReturn(Optional.of(ChefWarning.builder().id(8L).build()));

        service.analyzeDeliveryAndAct(2L);

        verify(emails).sendDeliveryWarning(eq(2L), anyString(), eq(m), eq(true));
    }

    @Test
    void deliveryBelowThreshold_doesNothing() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_ratio", 0.01);
        m.put("report_count", 1);
        when(analysis.computeDeliveryMetrics(2L)).thenReturn(m);

        service.analyzeDeliveryAndAct(2L);

        verify(commands, never()).createDeliveryWarning(anyLong(), any());
    }

    // ---- escalation: analyze_and_act -----------------------------------------------------------------------

    @Test
    void analyze_noAction_whenMetricsNullOrBelowThresholds() {
        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(null);
        service.analyzeAndAct(2L, dish);

        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(metrics(0.01, 1, 1, 0.0, 0, 0));
        service.analyzeAndAct(2L, dish);

        verify(commands, never()).applyFullLock(anyLong(), anyString(), any());
        verify(commands, never()).applyDishLock(anyLong(), any(), anyString(), any());
        verify(commands, never()).createFoodQualityWarning(anyLong(), any(), any());
    }

    @Test
    void analyze_warning_createsWarning_emails_andMarksEmailSent() {
        Map<String, Object> m = metrics(0.05, 2, 2, 0, 0, 0);
        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(m);
        when(commands.createFoodQualityWarning(2L, dish, m)).thenReturn(Optional.of(ChefWarning.builder().id(4L).build()));

        service.analyzeAndAct(2L, dish);

        verify(emails).sendWarning(eq(2L), eq("Phở"), anyString());
        verify(commands).markWarningEmailSent(4L);
        verify(commands, never()).applyFullLock(anyLong(), anyString(), any());
    }

    @Test
    void analyze_duplicateWarningWithin24h_isSkipped_noEmail() {
        Map<String, Object> m = metrics(0.05, 2, 2, 0, 0, 0);
        when(analysis.computeChefMetrics(2L, null)).thenReturn(m);
        when(commands.createFoodQualityWarning(2L, null, m)).thenReturn(Optional.empty());

        service.analyzeAndAct(2L, null);

        verify(emails, never()).sendWarning(anyLong(), any(), anyString());
        verify(commands, never()).markWarningEmailSent(anyLong());
    }

    @Test
    void analyze_dishLock_locksTheDish_andEmails() {
        Map<String, Object> m = metrics(0.01, 1, 1, 0.1, 3, 40);
        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(m);
        ChefSuspension s = ChefSuspension.builder().uid(UUID.randomUUID()).build();
        when(commands.applyDishLock(eq(2L), eq(dish), anyString(), eq(m))).thenReturn(s);

        service.analyzeAndAct(2L, dish);

        verify(emails).sendDishLock(2L, "Phở", s);
        verify(commands, never()).applyFullLock(anyLong(), anyString(), any());
    }

    @Test
    void analyze_dishLock_skippedWhenThatDishIsAlreadyLocked() {
        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(metrics(0.01, 1, 1, 0.1, 3, 40));
        ChefSuspension existing = ChefSuspension.builder().suspensionType(SuspensionType.DISH_LOCK)
                .status(SuspensionStatus.ACTIVE).lockedDishUid(dish.getUid()).build();
        when(suspensionRepository.findFirstByChefIdAndStatusOrderByIdAsc(2L, SuspensionStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        service.analyzeAndAct(2L, dish);

        verify(commands, never()).applyDishLock(anyLong(), any(), anyString(), any());
    }

    @Test
    void analyze_dishLockOnAnotherDish_stillProceeds() {
        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(metrics(0.01, 1, 1, 0.1, 3, 40));
        ChefSuspension existing = ChefSuspension.builder().suspensionType(SuspensionType.DISH_LOCK)
                .status(SuspensionStatus.ACTIVE).lockedDishUid(UUID.randomUUID()).build();
        when(suspensionRepository.findFirstByChefIdAndStatusOrderByIdAsc(2L, SuspensionStatus.ACTIVE))
                .thenReturn(Optional.of(existing));
        when(commands.applyDishLock(anyLong(), any(), anyString(), any())).thenReturn(ChefSuspension.builder().build());

        service.analyzeAndAct(2L, dish);

        verify(commands).applyDishLock(eq(2L), eq(dish), anyString(), any());
    }

    @Test
    void analyze_fullLock_suspendsTheChef_andEmails() {
        Map<String, Object> m = metrics(0.5, 10, 5, 0, 0, 0);
        when(analysis.computeChefMetrics(2L, null)).thenReturn(m);
        ChefSuspension s = ChefSuspension.builder().uid(UUID.randomUUID()).build();
        when(commands.applyFullLock(eq(2L), anyString(), eq(m))).thenReturn(s);

        service.analyzeAndAct(2L, null);

        verify(emails).sendFullLock(2L, s);
    }

    @Test
    void analyze_neverDowngrades_anActiveFullLockBlocksEverything() {
        when(analysis.computeChefMetrics(2L, null)).thenReturn(metrics(0.5, 10, 5, 0, 0, 0));
        ChefSuspension full = ChefSuspension.builder().suspensionType(SuspensionType.FULL_LOCK)
                .status(SuspensionStatus.ACTIVE).build();
        when(suspensionRepository.findFirstByChefIdAndStatusOrderByIdAsc(2L, SuspensionStatus.ACTIVE))
                .thenReturn(Optional.of(full));

        service.analyzeAndAct(2L, null);

        verify(commands, never()).applyFullLock(anyLong(), anyString(), any());
        verify(commands, never()).createFoodQualityWarning(anyLong(), any(), any());
    }

    @Test
    void confirmReport_reanalyzesWithTheReportsDish() {
        ChefReport r = report(ReportCategory.FOOD_SAFETY);
        r.setDishUid(dish.getUid());
        when(commands.confirm(any(), any())).thenReturn(r);
        when(commands.dishOf(r)).thenReturn(dish);
        when(analysis.computeChefMetrics(2L, dish.getUid())).thenReturn(null);

        service.confirmReport(new CustomUser(), r.getUid());

        verify(analysis).computeChefMetrics(2L, dish.getUid());
    }

    @Test
    void liftSuspension_emailsAfterCommit() {
        ChefSuspension s = ChefSuspension.builder().chefId(2L).build();
        when(commands.lift(any(), any(), anyString())).thenReturn(new ReportCommandService.SuspensionOutcome(s, "Phở"));

        service.liftSuspension(new CustomUser(), UUID.randomUUID(), "ok");

        verify(emails).sendLifted(2L, s, "Phở");
    }

    // ---- chef status -----------------------------------------------------------------------------------------

    @Test
    void currentSuspension_reportsProfileFlagsAndTheActiveOrAppealingSuspension() {
        CustomUser chef = new CustomUser();
        chef.setId(2L);
        ChefProfile p = ChefProfile.builder().isAcceptingOrders(false).suspensionLevel(ChefSuspensionLevel.SUSPENDED).build();
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.of(p));
        ChefSuspension s = ChefSuspension.builder().uid(UUID.randomUUID()).chefId(2L).suspensionType(SuspensionType.FULL_LOCK)
                .status(SuspensionStatus.APPEALING).triggerSource(com.amomeal.marketplace.report.entity.SuspensionTrigger.SYSTEM)
                .reason("r").createdAt(java.time.Instant.now()).build();
        when(suspensionRepository.findFirstByChefIdAndStatusInOrderByCreatedAtDescIdDesc(eq(2L), any()))
                .thenReturn(Optional.of(s));

        ChefSuspensionStatusResponse out = service.currentSuspension(chef);

        assertThat(out.isAcceptingOrders()).isFalse();
        assertThat(out.suspensionLevel()).isEqualTo("SUSPENDED");
        assertThat(out.activeSuspension().status()).isEqualTo("APPEALING");
    }

    @Test
    void currentSuspension_chefWithoutProfile_isAnUncaught500() {
        CustomUser chef = new CustomUser();
        chef.setId(2L);
        when(chefProfileRepository.findByUserId(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentSuspension(chef)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ---- request validation (schema validators) ----------------------------------------------------------------

    @Test
    void createRequest_validators() {
        UUID id = UUID.randomUUID();
        // description too short / blank
        assertThatThrownBy(() -> new CreateReportRequest(id, null, null, "FOOD_SAFETY", "ngắn", null).validated())
                .isInstanceOf(ReportValidationException.class);
        assertThatThrownBy(() -> new CreateReportRequest(id, null, null, "FOOD_SAFETY", "   ", null).validated())
                .isInstanceOf(ReportValidationException.class);
        // order-required category needs order_uid
        assertThatThrownBy(() -> new CreateReportRequest(null, 1L, null, "FOOD_SAFETY", "mô tả đủ dài rồi", null).validated())
                .isInstanceOf(ReportValidationException.class);
        // platform category needs a target and evidence
        assertThatThrownBy(() -> new CreateReportRequest(null, null, null, "FRAUD", "mô tả đủ dài rồi", id).validated())
                .isInstanceOf(ReportValidationException.class);
        assertThatThrownBy(() -> new CreateReportRequest(null, 1L, null, "FRAUD", "mô tả đủ dài rồi", null).validated())
                .isInstanceOf(ReportValidationException.class);
        // unknown category
        assertThatThrownBy(() -> new CreateReportRequest(id, null, null, "NOPE", "mô tả đủ dài rồi", null).validated())
                .isInstanceOf(ReportValidationException.class);
        // valid, description stripped
        CreateReportRequest.Validated ok = new CreateReportRequest(id, null, null, "FOOD_SAFETY", "  mô tả đủ dài rồi  ", null).validated();
        assertThat(ok.description()).isEqualTo("mô tả đủ dài rồi");
        assertThat(ok.category()).isEqualTo(ReportCategory.FOOD_SAFETY);
        assertThat(new CreateReportRequest(null, 1L, null, "FRAUD", "mô tả đủ dài rồi", id).validated().category())
                .isEqualTo(ReportCategory.FRAUD);
    }
}
