// 1. Sub-model tái sử dụng cho các chỉ số dinh dưỡng (Macros)
class MacroNutrientsModel {
  final num? proteinG;
  final num? lipidG;
  final num? carbG;
  final num? sodiumMg;
  final num? fiberG;

  MacroNutrientsModel({
    this.proteinG,
    this.lipidG,
    this.carbG,
    this.sodiumMg,
    this.fiberG,
  });

  factory MacroNutrientsModel.fromJson(Map<String, dynamic> json) {
    return MacroNutrientsModel(
      proteinG: json['protein_g'] as num?,
      lipidG: json['lipid_g'] as num?,
      carbG: json['carb_g'] as num?,
      sodiumMg: json['sodium_mg'] as num?,
      fiberG: json['fiber_g'] as num?,
    );
  }
}

// 2. Model chính ôm toàn bộ Response
class DailyNutritionSummaryModel {
  final String? date;
  final num? bmrKcal;
  final num? tdeeKcal;
  final MacroNutrientsModel? target;
  final MacroNutrientsModel? consumed;
  final MacroNutrientsModel? remaining;

  DailyNutritionSummaryModel({
    this.date,
    this.bmrKcal,
    this.tdeeKcal,
    this.target,
    this.consumed,
    this.remaining,
  });

  factory DailyNutritionSummaryModel.fromJson(Map<String, dynamic> json) {
    return DailyNutritionSummaryModel(
      date: json['date'] as String?,
      bmrKcal: json['bmr_kcal'] as num?,
      tdeeKcal: json['tdee_kcal'] as num?,
      // Tái sử dụng MacroNutrientsModel để parse data
      target: json['target'] != null ? MacroNutrientsModel.fromJson(json['target']) : null,
      consumed: json['consumed'] != null ? MacroNutrientsModel.fromJson(json['consumed']) : null,
      remaining: json['remaining'] != null ? MacroNutrientsModel.fromJson(json['remaining']) : null,
    );
  }
}