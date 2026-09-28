import 'package:testing/features/home/models/dish_model.dart';
import 'package:testing/features/recommend/models/better_alternative_dish_model.dart';

class BetterDishResponse {
  final String issue;
  final String sourceDishUid;
  final List<BetterAlternativeDishModel> items;

  BetterDishResponse({
    required this.issue,
    required this.sourceDishUid,
    required this.items,
  });

  factory BetterDishResponse.fromJson(Map<String, dynamic> json) {
    return BetterDishResponse(
      issue: json['issue'] ?? 'Unknown Issue',
      sourceDishUid: json['source_dish_uid'] ?? '',
      items: json['items'] != null
          ? (json['items'] as List).map((i) => BetterAlternativeDishModel.fromJson(i)).toList()
          : [],
    );
  }
}