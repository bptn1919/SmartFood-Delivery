class NutritionTotal {
  final double energy, protein, lipid, carbohydrate, fiber, natri, cholesterol;

  NutritionTotal({
    required this.energy, required this.protein, required this.lipid, 
    required this.carbohydrate, required this.fiber, required this.natri, 
    required this.cholesterol
  });

  factory NutritionTotal.fromJson(Map<String, dynamic> json) {
    return NutritionTotal(
      energy: (json['energy'] ?? 0).toDouble(),
      protein: (json['protein'] ?? 0).toDouble(),
      lipid: (json['lipid'] ?? 0).toDouble(),
      carbohydrate: (json['carbohydrate'] ?? 0).toDouble(),
      fiber: (json['fiber'] ?? 0).toDouble(),
      natri: (json['natri'] ?? 0).toDouble(),
      cholesterol: (json['cholesterol'] ?? 0).toDouble(),
    );
  }
}

class IngredientNutrition {
  final String name; 
  final double weight, energy, protein, lipid, carbohydrate, fiber, natri, cholesterol;

  IngredientNutrition({
    required this.name, required this.weight, required this.energy, 
    required this.protein, required this.lipid, required this.carbohydrate, 
    required this.fiber, required this.natri, required this.cholesterol
  });

  factory IngredientNutrition.fromJson(Map<String, dynamic> json) {
    return IngredientNutrition(
      // 👇 TECH LEAD FIX: Map chính xác key từ API BE
      name: json['ingredient_name'] ?? 'Unknown', 
      weight: (json['weight'] ?? 0).toDouble(),
      energy: (json['energy'] ?? 0).toDouble(),
      protein: (json['protein'] ?? 0).toDouble(),
      lipid: (json['lipid'] ?? 0).toDouble(),
      carbohydrate: (json['carbohydrate'] ?? 0).toDouble(),
      fiber: (json['fiber'] ?? 0).toDouble(),
      natri: (json['natri'] ?? 0).toDouble(),
      cholesterol: (json['cholesterol'] ?? 0).toDouble(),
    );
  }
}

class DishIngredientsResponse {
  final double? confidenceOfDish;
  final String? confidenceText;
  final String? note;
  final List<IngredientNutrition> ingredients;
  final NutritionTotal? nutritionTotal;

  DishIngredientsResponse({
    this.confidenceOfDish, this.confidenceText, this.note, 
    required this.ingredients, this.nutritionTotal
  });

  factory DishIngredientsResponse.fromJson(Map<String, dynamic> json) {
    return DishIngredientsResponse(
      confidenceOfDish: json['confidence_of_dish'] != null ? (json['confidence_of_dish']).toDouble() : null,
      confidenceText: json['confidence_text'],
      note: json['note'],
      ingredients: json['ingredients'] != null 
          ? (json['ingredients'] as List).map((i) => IngredientNutrition.fromJson(i)).toList() 
          : [],
      nutritionTotal: json['nutrition_total'] != null 
          ? NutritionTotal.fromJson(json['nutrition_total']) 
          : null,
    );
  }
}