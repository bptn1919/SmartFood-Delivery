class NutritionImpact {
  final num? proteinG;
  final num? lipidG;
  final num? carbG;
  final num? sodiumMg;
  final num? fiberG;

  NutritionImpact({
    this.proteinG,
    this.lipidG,
    this.carbG,
    this.sodiumMg,
    this.fiberG,
  });

  factory NutritionImpact.fromJson(Map<String, dynamic> json) {
    return NutritionImpact(
      proteinG: json['protein_g'] as num?,
      lipidG: json['lipid_g'] as num?,
      carbG: json['carb_g'] as num?,
      sodiumMg: json['sodium_mg'] as num?,
      fiberG: json['fiber_g'] as num?,
    );
  }
}

class NutritionRecommendedDishModel {
  final String? dishUid;
  final String? dishName;
  final String? publicUrl;
  final num? price;
  final num? avgRating;
  final num? baseRecommendationScore;
  final num? macroMatchScore;
  final num? finalScore;
  final num? suggestedServings;
  final NutritionImpact? nutritionImpact;
  final List<String>? reasons;

  NutritionRecommendedDishModel({
    this.dishUid,
    this.dishName,
    this.publicUrl,
    this.price,
    this.avgRating,
    this.baseRecommendationScore,
    this.macroMatchScore,
    this.finalScore,
    this.suggestedServings,
    this.nutritionImpact,
    this.reasons,
  });

  factory NutritionRecommendedDishModel.fromJson(Map<String, dynamic> json) {
    return NutritionRecommendedDishModel(
      dishUid: json['dish_uid'] as String?,
      dishName: json['dish_name'] as String?,
      publicUrl: json['public_url'] as String?,
      price: json['price'] as num?,
      avgRating: json['avg_rating'] as num?,
      baseRecommendationScore: json['base_recommendation_score'] as num?,
      macroMatchScore: json['macro_match_score'] as num?,
      finalScore: json['final_score'] as num?,
      suggestedServings: json['suggested_servings'] as num?,
      nutritionImpact: json['nutrition_impact'] != null 
          ? NutritionImpact.fromJson(json['nutrition_impact']) 
          : null,
      reasons: (json['reasons'] as List<dynamic>?)?.map((e) => e.toString()).toList(),
    );
  }
}