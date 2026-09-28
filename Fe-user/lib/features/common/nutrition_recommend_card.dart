import 'package:flutter/material.dart';
import 'package:testing/features/recommend/models/nutrition_recommended_dish_model.dart';

import 'app_theme.dart';

class NutritionRecommendationCard extends StatelessWidget {
  final NutritionRecommendedDishModel dish;
  final VoidCallback onTap;

  const NutritionRecommendationCard({
    Key? key,
    required this.dish,
    required this.onTap,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final String imageUrl = dish.publicUrl ?? "https://via.placeholder.com/150";
    final String? topReason = (dish.reasons != null && dish.reasons!.isNotEmpty)
        ? dish.reasons!.first
        : null;
    final impact = dish.nutritionImpact;

    // Tính toán độ match (Giả sử BE trả về hệ số 0.0 -> 1.0 hoặc 0 -> 100)
    // Ở đây tôi giả định BE trả về hệ số 100 (VD: 95.5)
    final double matchScore = (dish.macroMatchScore ?? 0).toDouble();

    return Container(
      margin: AppInsets.cardBottom,
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadii.card),
        boxShadow: [
          BoxShadow(
              color: Colors.black.withOpacity(0.05),
              blurRadius: 10,
              offset: const Offset(0, 4))
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(AppRadii.card),
          onTap: onTap,
          child: Padding(
            padding: AppInsets.allXl,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // ==========================================
                // TẦNG 1: THÔNG TIN CƠ BẢN & ĐỘ MATCH NUTRITION
                // ==========================================
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(10),
                      child: Image.network(
                        imageUrl,
                        width: 80,
                        height: 80,
                        cacheWidth: 160,
                        cacheHeight: 160,
                        fit: BoxFit.cover,
                        errorBuilder: (c, e, s) => Container(
                            width: 80,
                            height: 80,
                            color: Colors.grey[200],
                            child: const Icon(Icons.broken_image,
                                color: Colors.grey)),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            dish.dishName ?? "Unknown Dish",
                            style: const TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: Colors.black87),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 4),

                          // Hiển thị phần trăm độ phù hợp Macros
                          Row(
                            children: [
                              Icon(Icons.track_changes_rounded,
                                  size: 14, color: Colors.green[600]),
                              const SizedBox(width: 4),
                              Text(
                                "${matchScore.toStringAsFixed(0)}% Macro Match",
                                style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700,
                                    color: Colors.green[700]),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),

                          // Hiển thị khẩu phần gợi ý
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                                color: Colors.blue[50],
                                borderRadius: BorderRadius.circular(4)),
                            child: Text(
                              "Suggested: ${dish.suggestedServings ?? 1} serving(s)",
                              style: TextStyle(
                                  fontSize: 11,
                                  color: Colors.blue[700],
                                  fontWeight: FontWeight.w600),
                            ),
                          )
                        ],
                      ),
                    ),
                    // Giá tiền góc phải
                    Text(
                      "${(dish.price ?? 0).toStringAsFixed(0)}đ",
                      style: AppTextStyles.recommendedPrice,
                    ),
                  ],
                ),

                const SizedBox(height: 12),
                const Divider(
                    height: 1, thickness: 1, color: AppColors.divider),
                const SizedBox(height: 12),

                // ==========================================
                // TẦNG 2: THÔNG SỐ MACROS (P - C - F)
                // ==========================================
                if (impact != null)
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceAround,
                    children: [
                      _buildMacroBadge("Protein", "${impact.proteinG ?? 0}g",
                          Colors.red[100]!, Colors.red[800]!),
                      _buildMacroBadge("Carbs", "${impact.carbG ?? 0}g",
                          Colors.orange[100]!, Colors.orange[800]!),
                      _buildMacroBadge("Fat", "${impact.lipidG ?? 0}g",
                          Colors.yellow[200]!, Colors.yellow[900]!),
                    ],
                  ),

                // ==========================================
                // TẦNG 3: LÝ DO GỢI Ý BỞI AI
                // ==========================================
                if (topReason != null) ...[
                  const SizedBox(height: 12),
                  Container(
                    width: double.infinity,
                    padding:
                        const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    decoration: BoxDecoration(
                      color: Colors.green
                          .withOpacity(0.05), // Dùng tone xanh lá cho sức khỏe
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Icon(Icons.health_and_safety_rounded,
                            color: Colors.green, size: 16),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            topReason,
                            style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: Colors.green[800],
                                fontStyle: FontStyle.italic),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  ),
                ]
              ],
            ),
          ),
        ),
      ),
    );
  }

  // Hàm helper để vẽ các badge dinh dưỡng cho gọn code
  Widget _buildMacroBadge(
      String label, String value, Color bgColor, Color textColor) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration:
          BoxDecoration(color: bgColor, borderRadius: BorderRadius.circular(8)),
      child: Column(
        children: [
          Text(label,
              style: TextStyle(
                  fontSize: 10, color: textColor, fontWeight: FontWeight.bold)),
          Text(value,
              style: TextStyle(
                  fontSize: 13, color: textColor, fontWeight: FontWeight.w900)),
        ],
      ),
    );
  }
}
