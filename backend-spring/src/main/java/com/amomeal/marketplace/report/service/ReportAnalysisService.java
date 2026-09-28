package com.amomeal.marketplace.report.service;

import com.amomeal.marketplace.common.util.PyMath;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.report.entity.ChefReport;
import com.amomeal.marketplace.report.entity.ReportCategory;
import com.amomeal.marketplace.report.entity.ReportStatus;
import com.amomeal.marketplace.report.repository.ChefReportRepository;
import com.amomeal.marketplace.report.repository.ReportOrderRepository;
import com.amomeal.marketplace.report.repository.ReportReviewRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Port of ../../backend/report/services/analysis.py: category groups, thresholds, credibility weights and the
 * chef / dish / delivery metric computation + the WARNING / DISH_LOCK / FULL_LOCK / ADMIN_ALERT decisions.
 * Constants are the Django values verbatim.
 */
@Service
public class ReportAnalysisService {

    // ---- Category groups ---------------------------------------------------------------------------------
    public static final Set<ReportCategory> FOOD_QUALITY_CATEGORIES = EnumSet.of(
            ReportCategory.FOOD_SAFETY, ReportCategory.FOOD_QUALITY, ReportCategory.HYGIENE);
    public static final Set<ReportCategory> DELIVERY_CATEGORIES = EnumSet.of(
            ReportCategory.WRONG_ITEM, ReportCategory.MISSING_ITEM);
    public static final Set<ReportCategory> PAYMENT_REQUIRED_CATEGORIES = EnumSet.of(
            ReportCategory.PAYMENT_ISSUE, ReportCategory.REFUND_ISSUE);
    public static final Set<ReportCategory> FINANCIAL_CATEGORIES = EnumSet.of(
            ReportCategory.FINANCIAL, ReportCategory.PAYMENT_ISSUE, ReportCategory.REFUND_ISSUE);
    public static final Set<ReportCategory> ORDER_REQUIRED_CATEGORIES = orderRequired();
    public static final Set<ReportCategory> PLATFORM_EVIDENCE_REQUIRED = EnumSet.of(
            ReportCategory.IMPERSONATION, ReportCategory.FAKE_BUSINESS, ReportCategory.INAPPROPRIATE,
            ReportCategory.FRAUD, ReportCategory.POLICY_VIOLATION, ReportCategory.ILLEGAL_ACTIVITY,
            ReportCategory.FINANCIAL);

    private static Set<ReportCategory> orderRequired() {
        Set<ReportCategory> s = EnumSet.noneOf(ReportCategory.class);
        s.addAll(FOOD_QUALITY_CATEGORIES);
        s.addAll(DELIVERY_CATEGORIES);
        s.addAll(PAYMENT_REQUIRED_CATEGORIES);
        return s;
    }

    // ---- Review issue labels (AI review model) -------------------------------------------------------------
    public static final List<String> FOOD_SAFETY_ISSUES = List.of(
            "tanh", "hôi", "hư thiu", "sạn", "mốc", "ôi", "sống", "dị vật", "bẩn", "chua");
    public static final List<String> DELIVERY_ISSUES = List.of(
            "giao sai", "giao thiếu", "giao chậm", "thiếu món", "sai món");

    // ---- Thresholds ------------------------------------------------------------------------------------------
    public static final double REVIEW_SIGNAL_FACTOR = 0.35;
    public static final int MIN_COMPLETED_ORDERS_CHEF = 50;
    public static final int MIN_COMPLETED_ORDERS_DISH = 30;
    public static final int REPORT_WINDOW_DAYS = 30;
    public static final double WARNING_CHEF_RATIO = 0.04;
    public static final double FULL_LOCK_CHEF_RATIO = 0.08;
    public static final int FULL_LOCK_MIN_REPORTS = 10;
    public static final int FULL_LOCK_MIN_UNIQUE_REPORTERS = 5;
    public static final double DISH_LOCK_RATIO = 0.05;
    public static final int DISH_LOCK_MIN_REPORTS = 3;
    public static final int CUSTOMER_REPORT_DAILY_LIMIT = 5;

    public static final double WEIGHT_PLAIN_REPORT = 1.0;
    public static final double WEIGHT_HAS_TEXT = 1.5;
    public static final double WEIGHT_HAS_IMAGE = 2.0;
    public static final double WEIGHT_AI_CONFIRMED = 3.0;
    public static final double WEIGHT_ADMIN = 5.0;

    public static final int DELIVERY_MIN_COMPLETED_ORDERS = 20;
    public static final double DELIVERY_WARNING_RATIO = 0.05;
    public static final double DELIVERY_ADMIN_ALERT_RATIO = 0.10;
    public static final double DELIVERY_REVIEW_SIGNAL_FACTOR = 0.25;

    private static final Set<ReportStatus> COUNTED_STATUSES = EnumSet.of(
            ReportStatus.PENDING, ReportStatus.REVIEWED, ReportStatus.ACTED_ON);

    /** Django's (action, reason) dict. */
    public record Decision(String action, String reason) {
    }

    private final ChefReportRepository reportRepository;
    private final ReportOrderRepository orderRepository;
    private final ReportReviewRepository reviewRepository;

    public ReportAnalysisService(ChefReportRepository reportRepository, ReportOrderRepository orderRepository,
                                 ReportReviewRepository reviewRepository) {
        this.reportRepository = reportRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Weights + rate limit
    // ------------------------------------------------------------------------------------------------------------

    /** Django: compute_initial_weight. {@code description} is the already-stripped text. */
    public double computeInitialWeight(boolean hasEvidence, String description, Long reporterId) {
        double base;
        if (hasEvidence) {
            base = WEIGHT_HAS_IMAGE;
        } else if (description.strip().length() >= 30) {
            base = WEIGHT_HAS_TEXT;
        } else {
            base = WEIGHT_PLAIN_REPORT;
        }
        long dismissed = reportRepository.countByReporterIdAndStatus(reporterId, ReportStatus.DISMISSED);
        long acted = reportRepository.countByReporterIdAndStatusIn(
                reporterId, List.of(ReportStatus.REVIEWED, ReportStatus.ACTED_ON));
        long totalClosed = dismissed + acted;
        if (totalClosed > 0) {
            double dismissRate = (double) dismissed / totalClosed;
            double historyFactor = Math.max(0.6, 1.0 - dismissRate * 0.4);
            base = PyMath.round(base * historyFactor, 2);
        }
        return base;
    }

    /** Django: compute_weight_after_ai. */
    public static double computeWeightAfterAi(double currentWeight, String aiSeverity, boolean aiFoodSafetyRisk) {
        if (aiFoodSafetyRisk && ("HIGH".equals(aiSeverity) || "CRITICAL".equals(aiSeverity))) {
            return Math.max(currentWeight, WEIGHT_AI_CONFIRMED);
        }
        return currentWeight;
    }

    /** Django: check_customer_rate_limit - true while the customer is still under the 24h limit. */
    public boolean checkCustomerRateLimit(Long reporterId) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        long count = reportRepository.countByReporterIdAndCreatedAtGreaterThanEqualAndDeletedFalse(reporterId, cutoff);
        return count < CUSTOMER_REPORT_DAILY_LIMIT;
    }

    private static Instant windowCutoff() {
        return Instant.now().minus(Duration.ofDays(REPORT_WINDOW_DAYS));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Review signal
    // ------------------------------------------------------------------------------------------------------------

    /** Django: _compute_review_signal -> (weighted_sum, order_count); one order = one signal (max weight, capped at 1). */
    double[] computeReviewSignal(Long chefId, UUID dishUid, Instant cutoff) {
        List<Object[]> groups = dishUid == null
                ? reviewRepository.maxWeightByOrder(chefId, FOOD_SAFETY_ISSUES, cutoff)
                : reviewRepository.maxWeightByOrderForDish(chefId, FOOD_SAFETY_ISSUES, cutoff, dishUid);
        double total = 0.0;
        for (Object[] g : groups) {
            total += Math.min(((Number) g[1]).doubleValue(), 1.0);
        }
        return new double[]{PyMath.round(total * REVIEW_SIGNAL_FACTOR, 4), groups.size()};
    }

    // ------------------------------------------------------------------------------------------------------------
    // Chef / dish metrics
    // ------------------------------------------------------------------------------------------------------------

    /** Django: compute_chef_metrics. Null when the chef has fewer than MIN_COMPLETED_ORDERS_CHEF completed orders. */
    public Map<String, Object> computeChefMetrics(Long chefId, UUID dishUid) {
        Instant cutoff = windowCutoff();
        long completedOrders = orderRepository.countByChef(chefId, OrderStatus.COMPLETED, cutoff);
        if (completedOrders < MIN_COMPLETED_ORDERS_CHEF) {
            return null;
        }

        List<ChefReport> chefReports = reportRepository
                .findByChefIdAndCategoryInAndCreatedAtGreaterThanEqualAndDeletedFalseAndStatusIn(
                        chefId, ORDER_REQUIRED_CATEGORIES, cutoff, COUNTED_STATUSES);
        int chefReportCount = chefReports.size();
        double reportWeightedSum = 0.0;
        // Django: values("reporter_id").distinct().count() - a NULL reporter counts as one distinct value.
        Set<Long> reporters = new HashSet<>();
        for (ChefReport r : chefReports) {
            reportWeightedSum += r.getCredibilityWeight();
            reporters.add(r.getReporterId());
        }
        int uniqueReporters = reporters.size();

        double[] review = computeReviewSignal(chefId, null, cutoff);
        double reviewSignalSum = review[0];
        int reviewCount = (int) review[1];

        double combined = reportWeightedSum + reviewSignalSum;
        double chefRatio = completedOrders > 0 ? combined / completedOrders : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completed_orders", completedOrders);
        result.put("chef_report_count", chefReportCount);
        result.put("unique_reporters", uniqueReporters);
        result.put("report_weighted_sum", PyMath.round(reportWeightedSum, 4));
        result.put("review_count", reviewCount);
        result.put("review_signal_sum", reviewSignalSum);
        result.put("combined_weighted_sum", PyMath.round(combined, 4));
        result.put("chef_ratio", PyMath.round(chefRatio, 4));
        result.put("window_days", REPORT_WINDOW_DAYS);

        if (dishUid != null) {
            long dishOrders = orderRepository.countOrdersWithDish(dishUid, chefId, OrderStatus.COMPLETED, cutoff);
            List<ChefReport> dishReports = new ArrayList<>();
            for (ChefReport r : chefReports) {
                if (Objects.equals(r.getDishUid(), dishUid)) {
                    dishReports.add(r);
                }
            }
            int dishReportCount = dishReports.size();
            double dishReportSum = 0.0;
            for (ChefReport r : dishReports) {
                dishReportSum += r.getCredibilityWeight();
            }
            double[] dishReview = computeReviewSignal(chefId, dishUid, cutoff);
            double dishCombined = dishReportSum + dishReview[0];
            double dishRatio = dishOrders > 0 ? dishCombined / dishOrders : 0.0;

            result.put("dish_uid", dishUid.toString());
            result.put("dish_orders", dishOrders);
            result.put("dish_report_count", dishReportCount);
            result.put("dish_report_sum", PyMath.round(dishReportSum, 4));
            result.put("dish_review_count", (int) dishReview[1]);
            result.put("dish_review_sum", PyMath.round(dishReview[0], 4));
            result.put("dish_combined", PyMath.round(dishCombined, 4));
            result.put("dish_ratio", PyMath.round(dishRatio, 4));
        }
        return result;
    }

    /** Django: decide_action (priority FULL_LOCK > DISH_LOCK > WARNING > NONE). */
    public static Decision decideAction(Map<String, Object> metrics) {
        if (metrics == null) {
            return new Decision("NONE", "Chưa đủ dữ liệu");
        }
        double chefRatio = num(metrics, "chef_ratio");
        long chefCount = (long) num(metrics, "chef_report_count");
        long uniqueReporters = (long) num(metrics, "unique_reporters");
        long reviewCount = (long) num(metrics, "review_count");
        double dishRatio = num(metrics, "dish_ratio");
        long dishCount = (long) num(metrics, "dish_report_count");
        long dishOrders = (long) num(metrics, "dish_orders");

        if (chefRatio >= FULL_LOCK_CHEF_RATIO && chefCount >= FULL_LOCK_MIN_REPORTS
                && uniqueReporters >= FULL_LOCK_MIN_UNIQUE_REPORTERS) {
            return new Decision("FULL_LOCK", "Tỷ lệ phản ánh kết hợp " + pct(chefRatio, 1) + " vượt ngưỡng "
                    + pct(FULL_LOCK_CHEF_RATIO, 0) + " — gồm " + chefCount + " phản ánh trực tiếp từ "
                    + uniqueReporters + " khách khác nhau và " + reviewCount
                    + " đánh giá an toàn thực phẩm từ AI trong " + REPORT_WINDOW_DAYS + " ngày.");
        }
        if (dishOrders >= MIN_COMPLETED_ORDERS_DISH && dishRatio >= DISH_LOCK_RATIO && dishCount >= DISH_LOCK_MIN_REPORTS) {
            return new Decision("DISH_LOCK", "Tỷ lệ phản ánh kết hợp cho món này " + pct(dishRatio, 1)
                    + " vượt ngưỡng " + pct(DISH_LOCK_RATIO, 0) + " — gồm " + dishCount
                    + " phản ánh trực tiếp trên " + dishOrders + " đơn trong " + REPORT_WINDOW_DAYS + " ngày.");
        }
        if (chefRatio >= WARNING_CHEF_RATIO) {
            return new Decision("WARNING", "Tỷ lệ phản ánh kết hợp " + pct(chefRatio, 1) + " chạm ngưỡng cảnh báo "
                    + pct(WARNING_CHEF_RATIO, 0) + ". Vui lòng kiểm tra quy trình chế biến và bảo quản thực phẩm.");
        }
        return new Decision("NONE", "");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------------------------------------------------

    /** Django: compute_delivery_metrics. Null when fewer than DELIVERY_MIN_COMPLETED_ORDERS completed orders. */
    public Map<String, Object> computeDeliveryMetrics(Long chefId) {
        Instant cutoff = windowCutoff();
        long completedOrders = orderRepository.countByChef(chefId, OrderStatus.COMPLETED, cutoff);
        if (completedOrders < DELIVERY_MIN_COMPLETED_ORDERS) {
            return null;
        }
        List<ChefReport> reports = reportRepository
                .findByChefIdAndCategoryInAndCreatedAtGreaterThanEqualAndDeletedFalseAndStatusIn(
                        chefId, DELIVERY_CATEGORIES, cutoff, COUNTED_STATUSES);
        int reportCount = reports.size();
        double reportWeightedSum = 0.0;
        for (ChefReport r : reports) {
            reportWeightedSum += r.getCredibilityWeight();
        }
        List<Object[]> groups = reviewRepository.maxWeightByOrder(chefId, DELIVERY_ISSUES, cutoff);
        double reviewSum = 0.0;
        for (Object[] g : groups) {
            reviewSum += Math.min(((Number) g[1]).doubleValue(), 1.0);
        }
        double reviewSignalSum = reviewSum * DELIVERY_REVIEW_SIGNAL_FACTOR;

        double combined = reportWeightedSum + reviewSignalSum;
        double ratio = completedOrders > 0 ? combined / completedOrders : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completed_orders", completedOrders);
        result.put("report_count", reportCount);
        result.put("report_weighted_sum", PyMath.round(reportWeightedSum, 4));
        result.put("review_order_count", groups.size());
        result.put("review_signal_sum", PyMath.round(reviewSignalSum, 4));
        result.put("combined_weighted_sum", PyMath.round(combined, 4));
        result.put("delivery_ratio", PyMath.round(ratio, 4));
        result.put("window_days", REPORT_WINDOW_DAYS);
        return result;
    }

    /** Django: decide_delivery_action. */
    public static Decision decideDeliveryAction(Map<String, Object> metrics) {
        if (metrics == null) {
            return new Decision("NONE", "Chưa đủ dữ liệu");
        }
        double ratio = num(metrics, "delivery_ratio");
        long reportCount = (long) num(metrics, "report_count");
        if (ratio >= DELIVERY_ADMIN_ALERT_RATIO && reportCount >= 3) {
            return new Decision("ADMIN_ALERT", "Tỷ lệ giao hàng sai/thiếu " + pct(ratio, 1) + " vượt ngưỡng "
                    + pct(DELIVERY_ADMIN_ALERT_RATIO, 0) + " — " + reportCount + " phản ánh trong "
                    + REPORT_WINDOW_DAYS + " ngày.");
        }
        if (ratio >= DELIVERY_WARNING_RATIO) {
            return new Decision("WARNING", "Tỷ lệ giao hàng sai/thiếu " + pct(ratio, 1) + " chạm ngưỡng cảnh báo "
                    + pct(DELIVERY_WARNING_RATIO, 0) + ". Vui lòng kiểm tra quy trình đóng gói.");
        }
        return new Decision("NONE", "");
    }

    private static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** Python {@code f"{x:.{digits}%}"}. */
    static String pct(double ratio, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f%%", ratio * 100.0);
    }
}
