class MealLogItemModel {
  final String? uid;
  final String? source; // "APP", "EXTERNAL"
  final String? mealTime; // "BREAKFAST", "LUNCH", "DINNER", "SNACK"
  final String? dishUid;
  final String? dishName;
  final String? mealName;
  final num? quantityMultiplier; // Đây là số lượng (VD: 1.5, 0.5)
  final num? nutritionProteinG;
  final num? nutritionLipidG;
  final num? nutritionCarbG;
  final num? nutritionSodiumMg;
  final num? nutritionFiberG;
  final String? imageUrl;
  final num? price;

  MealLogItemModel({
    this.uid, this.source, this.mealTime, this.dishUid, this.dishName,
    this.mealName, this.quantityMultiplier, this.nutritionProteinG,
    this.nutritionLipidG, this.nutritionCarbG, this.nutritionSodiumMg,
    this.nutritionFiberG, this.imageUrl, this.price,
  });

  factory MealLogItemModel.fromJson(Map<String, dynamic> json) {
    return MealLogItemModel(
      uid: json['uid']?.toString(),
      source: json['source']?.toString(),
      mealTime: json['meal_time']?.toString(),
      dishUid: json['dish_uid']?.toString(),
      // Ưu tiên hiển thị tên món trên app, nếu rỗng thì dùng meal_name (do AI parse)
      dishName: json['dish_name']?.toString() ?? json['meal_name']?.toString(),
      mealName: json['meal_name']?.toString(),
      quantityMultiplier: json['quantity_multiplier'] as num?,
      nutritionProteinG: json['nutrition_protein_g'] as num?,
      nutritionLipidG: json['nutrition_lipid_g'] as num?,
      nutritionCarbG: json['nutrition_carb_g'] as num?,
      nutritionSodiumMg: json['nutrition_sodium_mg'] as num?,
      nutritionFiberG: json['nutrition_fiber_g'] as num?,
      imageUrl: json['image_url']?.toString(),
      price: json['price'] as num?,
    );
  }
}