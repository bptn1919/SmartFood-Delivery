class FoodPreferenceFeaturesModel {
  final String? uid;
  final int? user;
  final Map<String, dynamic>? allergicIngredientIds;
  final String? dietMode;
  final String? dietLevel;
  final String? allergyMode;
  final Map<String, dynamic>? favoriteIngredientIds;
  final Map<String, dynamic>? favoriteDishIds;
  final List<dynamic>? embedding;

  FoodPreferenceFeaturesModel({
    this.uid,
    this.user,
    this.allergicIngredientIds,
    this.dietMode,
    this.dietLevel,
    this.allergyMode,
    this.favoriteIngredientIds,
    this.favoriteDishIds,
    this.embedding,
  });

  factory FoodPreferenceFeaturesModel.fromJson(Map<String, dynamic> json) {
    return FoodPreferenceFeaturesModel(
      uid: json['uid'] as String?,
      user: json['user'] as int?,
      // Ép kiểu an toàn cho các Dict (Map)
      allergicIngredientIds: json['allergic_ingredient_ids'] is Map 
          ? Map<String, dynamic>.from(json['allergic_ingredient_ids']) : null,
      dietMode: json['diet_mode'] as String?,
      dietLevel: json['diet_level'] as String?,
      allergyMode: json['allergy_mode'] as String?,
      favoriteIngredientIds: json['favorite_ingredient_ids'] is Map 
          ? Map<String, dynamic>.from(json['favorite_ingredient_ids']) : null,
      favoriteDishIds: json['favorite_dish_ids'] is Map 
          ? Map<String, dynamic>.from(json['favorite_dish_ids']) : null,
      // Ép kiểu cho mảng Vector AI
      embedding: json['embedding'] as List<dynamic>?,
    );
  }
}