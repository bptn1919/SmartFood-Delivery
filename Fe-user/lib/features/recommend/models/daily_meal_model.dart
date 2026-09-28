class DailyMealResponse {
  final DailySummary? summary;
  final List<MealItem> items;

  DailyMealResponse({this.summary, required this.items});

  factory DailyMealResponse.fromJson(Map<String, dynamic> json) {
    return DailyMealResponse(
      summary: json['summary'] != null ? DailySummary.fromJson(json['summary']) : null,
      items: json['items'] != null
          ? (json['items'] as List).map((i) => MealItem.fromJson(i)).toList()
          : [],
    );
  }
}

class DailySummary {
  final String date;
  final int bmrKcal;
  final int tdeeKcal;
  final MacroData target;
  final MacroData consumed;
  final MacroData remaining;

  DailySummary({
    required this.date,
    required this.bmrKcal,
    required this.tdeeKcal,
    required this.target,
    required this.consumed,
    required this.remaining,
  });

  factory DailySummary.fromJson(Map<String, dynamic> json) {
    return DailySummary(
      date: json['date'] ?? '',
      bmrKcal: (json['bmr_kcal'] as num?)?.toInt() ?? 0,
      tdeeKcal: (json['tdee_kcal'] as num?)?.toInt() ?? 0,
      target: MacroData.fromJson(json['target'] ?? {}),
      consumed: MacroData.fromJson(json['consumed'] ?? {}),
      remaining: MacroData.fromJson(json['remaining'] ?? {}),
    );
  }
}

class MacroData {
  final double protein;
  final double carb;
  final double lipid; // Đổi tên cho khớp với lipid_g
  final double sodium;
  final double fiber;

  MacroData({
    this.protein = 0, 
    this.carb = 0, 
    this.lipid = 0,
    this.sodium = 0,
    this.fiber = 0,
  });

  // 👇 TECH LEAD TRICK: Getter tự động tính Kcal từ Macro chuẩn khoa học
  int get kcal => ((protein * 4) + (carb * 4) + (lipid * 9)).round();

  factory MacroData.fromJson(Map<String, dynamic> json) {
    return MacroData(
      // 👇 TECH LEAD FIX: Dùng 0.0 để khóa cứng kiểu double, chống Null
      protein: (json['protein_g'] as num?)?.toDouble() ?? 0.0,
      carb: (json['carb_g'] as num?)?.toDouble() ?? 0.0,
      lipid: (json['lipid_g'] as num?)?.toDouble() ?? 0.0,
      sodium: (json['sodium_mg'] as num?)?.toDouble() ?? 0.0,
      fiber: (json['fiber_g'] as num?)?.toDouble() ?? 0.0,
    );
  }
}

class MealItem {
  final String uid;
  final String source;
  final String mealTime;
  final String dishName;
  final String? imageUrl;
  final double protein;
  final double carb;
  final double lipid;
  final double sodium;
  final double fiber;
  final double quantityMultiplier;

  MealItem({
    required this.uid,
    required this.source,
    required this.mealTime,
    required this.dishName,
    this.imageUrl,
    this.protein = 0,
    this.carb = 0,
    this.lipid = 0,
    this.sodium = 0,
    this.fiber = 0,
    this.quantityMultiplier = 1.0,
  });

  // Tương tự, tự tính calo cho từng món ăn
  int get calories => ((protein * 4) + (carb * 4) + (lipid * 9)).round();

  factory MealItem.fromJson(Map<String, dynamic> json) {
    return MealItem(
      uid: json['uid'] ?? '',
      source: json['source'] ?? '',
      mealTime: json['meal_time'] ?? '',
      dishName: json['dish_name'] ?? json['meal_name'] ?? 'Unknown Dish',
      imageUrl: json['image_url'],
      // 👇 TECH LEAD FIX: Áp dụng tương tự cho các món ăn
      protein: (json['nutrition_protein_g'] as num?)?.toDouble() ?? 0.0,
      carb: (json['nutrition_carb_g'] as num?)?.toDouble() ?? 0.0,
      lipid: (json['nutrition_lipid_g'] as num?)?.toDouble() ?? 0.0,
      sodium: (json['nutrition_sodium_mg'] as num?)?.toDouble() ?? 0.0,
      fiber: (json['nutrition_fiber_g'] as num?)?.toDouble() ?? 0.0,
      quantityMultiplier: (json['quantity_multiplier'] as num?)?.toDouble() ?? 1.0,
    );
  }
}