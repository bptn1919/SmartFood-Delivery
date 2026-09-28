// Nhớ import file chứa DailyNutritionSummaryModel của bạn vào đây
// import 'daily_nutrition_summary_model.dart';

import 'package:testing/features/recommend/models/daily_nutrition_summary_model.dart';

class ParseMealResponseModel {
  final DailyNutritionSummaryModel? summary;
  final int? parsedCount;
  final List<String>? unresolvedMeals;

  ParseMealResponseModel({
    this.summary,
    this.parsedCount,
    this.unresolvedMeals,
  });

  factory ParseMealResponseModel.fromJson(Map<String, dynamic> json) {
    return ParseMealResponseModel(
      // Tái sử dụng Model cũ để parse JSON cục summary
      summary: json['summary'] != null 
          ? DailyNutritionSummaryModel.fromJson(json['summary']) 
          : null,
      parsedCount: json['parsed_count'] as int?,
      unresolvedMeals: (json['unresolved_meals'] as List<dynamic>?)
          ?.map((e) => e.toString())
          .toList(),
    );
  }
}