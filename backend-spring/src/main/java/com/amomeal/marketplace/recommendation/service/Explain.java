package com.amomeal.marketplace.recommendation.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 1:1 port of ../../backend/recommendation/services/explain.py::build_reasons — fixed Vietnamese
 * rule-based reasons (not AI), at most 3. Strings are user-facing FE copy, kept verbatim.
 */
public final class Explain {

    private Explain() {
    }

    public static List<String> buildReasons(ScoredItem item) {
        List<String> reasons = new ArrayList<>();
        double mismatch = item.getPreferenceNutritionMismatchPenalty() != null
                ? item.getPreferenceNutritionMismatchPenalty()
                : item.getNutritionPenalty();

        if (item.getFavoriteScore() >= 0.7) {
            reasons.add("Phù hợp với sở thích của bạn");
        }
        if (item.getHistoryScore() >= 0.6) {
            reasons.add("Được đề xuất dựa trên lịch sử gần đây của bạn");
        }
        if (item.getIngredientMatchRatio() >= 0.5) {
            reasons.add("Nguyên liệu tương đồng với nhóm bạn yêu thích");
        }
        if (item.getIssuePenalty() <= 0.2) {
            reasons.add("Mức độ nhạy cảm với vấn đề liên quan thấp");
        }
        if (mismatch <= 0.3) {
            reasons.add("Cân bằng dinh dưỡng tương đối tốt với xu hướng ăn uống của bạn");
        }
        if (item.getDietAlignment() >= 0.65) {
            reasons.add("Phù hợp với chế độ ăn uống của bạn");
        }
        if (item.getBaseScore() >= 0.6) {
            reasons.add("Món ăn có chất lượng nền tảng tốt (đánh giá, phổ biến, độ mới)");
        }
        if (reasons.isEmpty()) {
            reasons.add("Đề xuất theo độ phổ biến và hành vi tương đồng");
        }
        return new ArrayList<>(reasons.subList(0, Math.min(3, reasons.size())));
    }
}
