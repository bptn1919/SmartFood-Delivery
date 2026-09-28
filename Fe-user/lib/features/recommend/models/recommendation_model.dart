// Model cho các chỉ số dinh dưỡng (dùng chung cho target, consumed, remaining)
import 'package:testing/features/recommend/models/nutrition_recommended_dish_model.dart';

class NutritionMacro {
  final double energy; // Frontend tự tính dựa trên macros
  final double protein;
  final double lipid;
  final double carbohydrate;
  final double fiber;
  final double sodium;

  NutritionMacro({
    this.energy = 0, 
    this.protein = 0, 
    this.lipid = 0, 
    this.carbohydrate = 0,
    this.fiber = 0,
    this.sodium = 0,
  });

  factory NutritionMacro.fromJson(Map<String, dynamic> json) {
    // 1. Map CHÍNH XÁC các key từ Backend trả về
    final double p = (json['protein_g'] ?? 0).toDouble();
    final double l = (json['lipid_g'] ?? 0).toDouble();
    final double c = (json['carb_g'] ?? 0).toDouble();
    final double f = (json['fiber_g'] ?? 0).toDouble();
    final double s = (json['sodium_mg'] ?? 0).toDouble();

    // 2. Tự tính Năng lượng (Kcal) từ các vi chất nếu BE không trả về
    // Công thức: (Protein x 4) + (Carb x 4) + (Fat x 9)
    final double calcEnergy = (p * 4) + (c * 4) + (l * 9);

    return NutritionMacro(
      energy: calcEnergy, // Gán số Kcal đã tính vào đây
      protein: p,
      lipid: l,
      carbohydrate: c,
      fiber: f,
      sodium: s,
    );
  }
}

// Model cho Summary
class NutritionSummary {
  final String date;
  final double bmrKcal;
  final double tdeeKcal;
  final NutritionMacro target;
  final NutritionMacro consumed;
  final NutritionMacro remaining;

  NutritionSummary({
    required this.date, required this.bmrKcal, required this.tdeeKcal,
    required this.target, required this.consumed, required this.remaining,
  });

  factory NutritionSummary.fromJson(Map<String, dynamic> json) {
    return NutritionSummary(
      date: json['date'] ?? '',
      bmrKcal: (json['bmr_kcal'] ?? 0).toDouble(),
      tdeeKcal: (json['tdee_kcal'] ?? 0).toDouble(),
      target: NutritionMacro.fromJson(json['target'] ?? {}),
      consumed: NutritionMacro.fromJson(json['consumed'] ?? {}),
      remaining: NutritionMacro.fromJson(json['remaining'] ?? {}),
    );
  }
}

// Model Tổng bọc toàn bộ Response
class BalancedRecommendationResponse {
  final NutritionSummary? summary;
  final List<NutritionRecommendedDishModel>? items; // Ghi chú: Chút nữa ta sẽ map cái này thành List<DishModel>

  BalancedRecommendationResponse({this.summary, required this.items});

  factory BalancedRecommendationResponse.fromJson(Map<String, dynamic> json) {
    return BalancedRecommendationResponse(
      summary: json['summary'] != null ? NutritionSummary.fromJson(json['summary']) : null,
      items: (json['items'] as List<dynamic>?)
          ?.map((e) => NutritionRecommendedDishModel.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}