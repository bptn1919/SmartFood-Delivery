package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ReportStatus;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ReportOrderRepository;
import com.amomeal.marketplace.report.repository.ReportReviewRepository;
import com.amomeal.marketplace.report.service.ReportAnalysisService.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportAnalysisServiceTest {

    @Mock ChefReportRepository reportRepository;
    @Mock ReportOrderRepository orderRepository;
    @Mock ReportReviewRepository reviewRepository;

    ReportAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ReportAnalysisService(reportRepository, orderRepository, reviewRepository);
    }

    private static Map<String, Object> metrics(double chefRatio, int chefCount, int unique, double dishRatio,
                                               int dishCount, int dishOrders) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chef_ratio", chefRatio);
        m.put("chef_report_count", chefCount);
        m.put("unique_reporters", unique);
        m.put("review_count", 2);
        m.put("dish_ratio", dishRatio);
        m.put("dish_report_count", dishCount);
        m.put("dish_orders", dishOrders);
        return m;
    }

    private static List<Object[]> rows(Object[]... r) {
        return java.util.Arrays.asList(r);
    }

    private static ChefReport report(double weight, Long reporterId) {
        return ChefReport.builder().credibilityWeight(weight).reporterId(reporterId).build();
    }

    // ---- decide_action ---------------------------------------------------------------------------------

    @Test
    void decideAction_nullMetrics_isNoneWithInsufficientDataReason() {
        Decision d = ReportAnalysisService.decideAction(null);
        assertThat(d.action()).isEqualTo("NONE");
        assertThat(d.reason()).isEqualTo("Chưa đủ dữ liệu");
    }

    @Test
    void decideAction_fullLock_needsRatioAndTenReportsAndFiveReporters() {
        assertThat(ReportAnalysisService.decideAction(metrics(0.08, 10, 5, 0, 0, 0)).action()).isEqualTo("FULL_LOCK");
        // each condition alone missing -> not FULL_LOCK (falls to WARNING since ratio >= 4%)
        assertThat(ReportAnalysisService.decideAction(metrics(0.0799, 10, 5, 0, 0, 0)).action()).isEqualTo("WARNING");
        assertThat(ReportAnalysisService.decideAction(metrics(0.08, 9, 5, 0, 0, 0)).action()).isEqualTo("WARNING");
        assertThat(ReportAnalysisService.decideAction(metrics(0.08, 10, 4, 0, 0, 0)).action()).isEqualTo("WARNING");
    }

    @Test
    void decideAction_fullLockReason_isTheVietnameseTemplate() {
        Decision d = ReportAnalysisService.decideAction(metrics(0.0833, 12, 6, 0, 0, 0));
        assertThat(d.reason()).isEqualTo("Tỷ lệ phản ánh kết hợp 8.3% vượt ngưỡng 8% — gồm 12 phản ánh trực tiếp "
                + "từ 6 khách khác nhau và 2 đánh giá an toàn thực phẩm từ AI trong 30 ngày.");
    }

    @Test
    void decideAction_dishLock_needsThirtyDishOrdersRatioAndThreeReports() {
        assertThat(ReportAnalysisService.decideAction(metrics(0.01, 1, 1, 0.05, 3, 30)).action()).isEqualTo("DISH_LOCK");
        assertThat(ReportAnalysisService.decideAction(metrics(0.01, 1, 1, 0.05, 3, 29)).action()).isEqualTo("NONE");
        assertThat(ReportAnalysisService.decideAction(metrics(0.01, 1, 1, 0.0499, 3, 30)).action()).isEqualTo("NONE");
        assertThat(ReportAnalysisService.decideAction(metrics(0.01, 1, 1, 0.05, 2, 30)).action()).isEqualTo("NONE");
    }

    @Test
    void decideAction_fullLockOutranksDishLock_andDishLockOutranksWarning() {
        assertThat(ReportAnalysisService.decideAction(metrics(0.5, 10, 5, 0.5, 5, 40)).action()).isEqualTo("FULL_LOCK");
        assertThat(ReportAnalysisService.decideAction(metrics(0.05, 5, 3, 0.5, 5, 40)).action()).isEqualTo("DISH_LOCK");
    }

    @Test
    void decideAction_warning_atFourPercent_elseNone() {
        assertThat(ReportAnalysisService.decideAction(metrics(0.04, 1, 1, 0, 0, 0)).action()).isEqualTo("WARNING");
        assertThat(ReportAnalysisService.decideAction(metrics(0.0399, 1, 1, 0, 0, 0)).action()).isEqualTo("NONE");
    }

    @Test
    void decideDeliveryAction_thresholds() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_ratio", 0.10);
        m.put("report_count", 3);
        assertThat(ReportAnalysisService.decideDeliveryAction(m).action()).isEqualTo("ADMIN_ALERT");
        m.put("report_count", 2); // not enough raw reports for the admin alert -> plain warning
        assertThat(ReportAnalysisService.decideDeliveryAction(m).action()).isEqualTo("WARNING");
        m.put("delivery_ratio", 0.05);
        assertThat(ReportAnalysisService.decideDeliveryAction(m).action()).isEqualTo("WARNING");
        m.put("delivery_ratio", 0.0499);
        assertThat(ReportAnalysisService.decideDeliveryAction(m).action()).isEqualTo("NONE");
        assertThat(ReportAnalysisService.decideDeliveryAction(null).action()).isEqualTo("NONE");
    }

    // ---- weights -----------------------------------------------------------------------------------------

    @Test
    void initialWeight_tiers_evidence2_longText1_5_plain1() {
        when(reportRepository.countByReporterIdAndStatus(any(), any())).thenReturn(0L);
        when(reportRepository.countByReporterIdAndStatusIn(any(), anyCollection())).thenReturn(0L);

        assertThat(service.computeInitialWeight(true, "x", 1L)).isEqualTo(2.0);
        assertThat(service.computeInitialWeight(false, "a".repeat(30), 1L)).isEqualTo(1.5);
        assertThat(service.computeInitialWeight(false, "a".repeat(29), 1L)).isEqualTo(1.0);
    }

    @Test
    void initialWeight_discountedForReportersWhoGetDismissed_atMostForty() {
        when(reportRepository.countByReporterIdAndStatus(1L, ReportStatus.DISMISSED)).thenReturn(1L);
        when(reportRepository.countByReporterIdAndStatusIn(eq(1L), anyCollection())).thenReturn(1L);
        // dismiss rate 0.5 -> factor 0.8
        assertThat(service.computeInitialWeight(true, "x", 1L)).isEqualTo(1.6);

        when(reportRepository.countByReporterIdAndStatus(2L, ReportStatus.DISMISSED)).thenReturn(10L);
        when(reportRepository.countByReporterIdAndStatusIn(eq(2L), anyCollection())).thenReturn(0L);
        // dismiss rate 1.0 -> factor 0.6 (the cap)
        assertThat(service.computeInitialWeight(true, "x", 2L)).isEqualTo(1.2);
    }

    @Test
    void weightAfterAi_raisedToThree_onlyForHighOrCriticalWithFoodSafetyRisk() {
        assertThat(ReportAnalysisService.computeWeightAfterAi(1.0, "HIGH", true)).isEqualTo(3.0);
        assertThat(ReportAnalysisService.computeWeightAfterAi(2.0, "CRITICAL", true)).isEqualTo(3.0);
        assertThat(ReportAnalysisService.computeWeightAfterAi(5.0, "CRITICAL", true)).isEqualTo(5.0); // never lowered
        assertThat(ReportAnalysisService.computeWeightAfterAi(1.0, "HIGH", false)).isEqualTo(1.0);
        assertThat(ReportAnalysisService.computeWeightAfterAi(1.0, "MEDIUM", true)).isEqualTo(1.0);
        assertThat(ReportAnalysisService.computeWeightAfterAi(1.0, null, true)).isEqualTo(1.0);
    }

    @Test
    void rateLimit_allowsFour_blocksTheFifth() {
        when(reportRepository.countByReporterIdAndCreatedAtGreaterThanEqualAndDeletedFalse(eq(1L), any())).thenReturn(4L);
        assertThat(service.checkCustomerRateLimit(1L)).isTrue();
        when(reportRepository.countByReporterIdAndCreatedAtGreaterThanEqualAndDeletedFalse(eq(1L), any())).thenReturn(5L);
        assertThat(service.checkCustomerRateLimit(1L)).isFalse();
    }

    // ---- metrics ------------------------------------------------------------------------------------------

    @Test
    void chefMetrics_null_whenFewerThanFiftyCompletedOrders() {
        when(orderRepository.countByChef(eq(7L), eq(OrderStatus.COMPLETED), any())).thenReturn(49L);
        assertThat(service.computeChefMetrics(7L, null)).isNull();
    }

    @Test
    void chefMetrics_combinesReportWeightsAndReviewSignal() {
        when(orderRepository.countByChef(eq(7L), eq(OrderStatus.COMPLETED), any())).thenReturn(100L);
        when(reportRepository.findByChefIdAndCategoryInAndCreatedAtGreaterThanEqualAndDeletedFalseAndStatusIn(
                eq(7L), anyCollection(), any(), anyCollection()))
                .thenReturn(List.of(report(2.0, 1L), report(3.0, 1L), report(1.0, 2L)));
        // two review-issue orders, weights capped at 1.0 -> (1.0 + 0.5) * 0.35 = 0.525
        when(reviewRepository.maxWeightByOrder(eq(7L), anyCollection(), any()))
                .thenReturn(rows(new Object[]{UUID.randomUUID(), 5.0}, new Object[]{UUID.randomUUID(), 0.5}));

        Map<String, Object> m = service.computeChefMetrics(7L, null);

        assertThat(m.get("completed_orders")).isEqualTo(100L);
        assertThat(m.get("chef_report_count")).isEqualTo(3);
        assertThat(m.get("unique_reporters")).isEqualTo(2);
        assertThat(m.get("report_weighted_sum")).isEqualTo(6.0);
        assertThat(m.get("review_count")).isEqualTo(2);
        assertThat(m.get("review_signal_sum")).isEqualTo(0.525);
        assertThat(m.get("combined_weighted_sum")).isEqualTo(6.525);
        assertThat(m.get("chef_ratio")).isEqualTo(com.amomeal.marketplace.common.util.PyMath.round(6.525 / 100, 4));
        assertThat(m.get("window_days")).isEqualTo(30);
        assertThat(m).doesNotContainKey("dish_uid");
    }

    @Test
    void chefMetrics_withDish_addsDishLevelMetrics() {
        UUID dish = UUID.randomUUID();
        when(orderRepository.countByChef(eq(7L), eq(OrderStatus.COMPLETED), any())).thenReturn(50L);
        when(orderRepository.countOrdersWithDish(eq(dish), eq(7L), eq(OrderStatus.COMPLETED), any())).thenReturn(40L);
        ChefReport onDish = report(2.0, 1L);
        onDish.setDishUid(dish);
        ChefReport other = report(3.0, 2L);
        other.setDishUid(UUID.randomUUID());
        when(reportRepository.findByChefIdAndCategoryInAndCreatedAtGreaterThanEqualAndDeletedFalseAndStatusIn(
                eq(7L), anyCollection(), any(), anyCollection())).thenReturn(List.of(onDish, other));
        when(reviewRepository.maxWeightByOrder(any(), anyCollection(), any())).thenReturn(List.of());
        when(reviewRepository.maxWeightByOrderForDish(any(), anyCollection(), any(), eq(dish)))
                .thenReturn(rows(new Object[]{UUID.randomUUID(), 1.0}));

        Map<String, Object> m = service.computeChefMetrics(7L, dish);

        assertThat(m.get("dish_uid")).isEqualTo(dish.toString());
        assertThat(m.get("dish_orders")).isEqualTo(40L);
        assertThat(m.get("dish_report_count")).isEqualTo(1);
        assertThat(m.get("dish_report_sum")).isEqualTo(2.0);
        assertThat(m.get("dish_review_count")).isEqualTo(1);
        assertThat(m.get("dish_review_sum")).isEqualTo(0.35);
        assertThat(m.get("dish_combined")).isEqualTo(2.35);
        assertThat(m.get("dish_ratio")).isEqualTo(com.amomeal.marketplace.common.util.PyMath.round(2.35 / 40, 4));
    }

    @Test
    void deliveryMetrics_nullBelowTwentyOrders_elseRatio() {
        when(orderRepository.countByChef(eq(7L), eq(OrderStatus.COMPLETED), any())).thenReturn(19L);
        assertThat(service.computeDeliveryMetrics(7L)).isNull();

        when(orderRepository.countByChef(eq(7L), eq(OrderStatus.COMPLETED), any())).thenReturn(20L);
        when(reportRepository.findByChefIdAndCategoryInAndCreatedAtGreaterThanEqualAndDeletedFalseAndStatusIn(
                eq(7L), anyCollection(), any(), anyCollection())).thenReturn(List.of(report(1.5, 1L), report(1.5, 2L)));
        when(reviewRepository.maxWeightByOrder(eq(7L), anyCollection(), any()))
                .thenReturn(rows(new Object[]{UUID.randomUUID(), 1.0}));

        Map<String, Object> m = service.computeDeliveryMetrics(7L);

        assertThat(m.get("report_count")).isEqualTo(2);
        assertThat(m.get("report_weighted_sum")).isEqualTo(3.0);
        assertThat(m.get("review_order_count")).isEqualTo(1);
        assertThat(m.get("review_signal_sum")).isEqualTo(0.25);
        assertThat(m.get("delivery_ratio")).isEqualTo(0.1625); // 3.25 / 20
    }
}
