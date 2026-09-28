class RecommendedDishModel {
  final String? dishUid;
  final String? dishName;
  final String? publicUrl; // 👈 NEW
  final num? price;
  final num? avgRating;
  final bool? isFavorite; // 👈 NEW
  final bool? allergyWarning; // 👈 NEW
  final num? score;
  final num? baseScore; // 👈 NEW
  final num? favoriteScore; // 👈 NEW
  final num? historyScore; // 👈 NEW
  final num? issuePenalty; // 👈 NEW
  final num? nutritionPenalty; // 👈 NEW
  final num? preferenceNutritionMismatchPenalty; // 👈 NEW
  final List<String>? reasons;

  RecommendedDishModel({
    this.dishUid,
    this.dishName,
    this.publicUrl,
    this.price,
    this.avgRating,
    this.isFavorite,
    this.allergyWarning,
    this.score,
    this.baseScore,
    this.favoriteScore,
    this.historyScore,
    this.issuePenalty,
    this.nutritionPenalty,
    this.preferenceNutritionMismatchPenalty,
    this.reasons,
  });

  factory RecommendedDishModel.fromJson(Map<String, dynamic> json) {
    return RecommendedDishModel(
      dishUid: json['dish_uid'] as String?,
      dishName: json['dish_name'] as String?,
      publicUrl: json['public_url'] as String?,
      price: json['price'] as num?,
      avgRating: json['avg_rating'] as num?,
      isFavorite: json['is_favorite'] as bool?,
      allergyWarning: json['allergy_warning'] as bool?,
      score: json['score'] as num?,
      baseScore: json['base_score'] as num?,
      favoriteScore: json['favorite_score'] as num?,
      historyScore: json['history_score'] as num?,
      issuePenalty: json['issue_penalty'] as num?,
      nutritionPenalty: json['nutrition_penalty'] as num?,
      preferenceNutritionMismatchPenalty: json['preference_nutrition_mismatch_penalty'] as num?,
      reasons: (json['reasons'] as List<dynamic>?)?.map((e) => e.toString()).toList(),
    );
  }
}

class RecommendationFeedResponse {
  final List<RecommendedDishModel>? items;
  final Map<String, dynamic>? meta;

  RecommendationFeedResponse({this.items, this.meta});

  factory RecommendationFeedResponse.fromJson(Map<String, dynamic> json) {
    return RecommendationFeedResponse(
      items: (json['items'] as List<dynamic>?)
          ?.map((e) => RecommendedDishModel.fromJson(e as Map<String, dynamic>))
          .toList(),
      meta: json['meta'] as Map<String, dynamic>?,
    );
  }
}