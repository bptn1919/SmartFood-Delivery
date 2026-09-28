// 1. Class phụ để parse các object target, consumed, remaining
class NutritionMacroModel {
  final num? proteinG;
  final num? lipidG;
  final num? carbG;
  final num? sodiumMg;
  final num? fiberG;

  NutritionMacroModel({
    this.proteinG, this.lipidG, this.carbG, this.sodiumMg, this.fiberG,
  });

  factory NutritionMacroModel.fromJson(Map<String, dynamic> json) {
    return NutritionMacroModel(
      proteinG: json['protein_g'] as num?,
      lipidG: json['lipid_g'] as num?,
      carbG: json['carb_g'] as num?,
      sodiumMg: json['sodium_mg'] as num?,
      fiberG: json['fiber_g'] as num?,
    );
  }
}

// 2. Class Profile chính
class NutritionProfileModel {
  // --- NHÓM 1: Các trường có thể chỉnh sửa (Dùng cho PUT) ---
  final int? age;
  final String? gender;
  final num? heightCm;
  final num? weightKg;
  final String? activityLevel;
  final String? goal;

  // --- NHÓM 2: Các trường thống kê chỉ đọc (Chỉ nhận từ GET) ---
  final String? date;
  final num? bmrKcal;
  final num? tdeeKcal;
  final NutritionMacroModel? target;
  final NutritionMacroModel? consumed;
  final NutritionMacroModel? remaining;

  NutritionProfileModel({
    // Nhóm 1
    this.age, this.gender, this.heightCm, this.weightKg, this.activityLevel, this.goal,
    // Nhóm 2
    this.date, this.bmrKcal, this.tdeeKcal, this.target, this.consumed, this.remaining,
  });

  factory NutritionProfileModel.fromJson(Map<String, dynamic> json) {
    return NutritionProfileModel(
      age: json['age'] as int?,
      gender: json['gender']?.toString(),
      heightCm: json['height_cm'] as num?,
      weightKg: json['weight_kg'] as num?,
      activityLevel: json['activity_level']?.toString(),
      goal: json['goal']?.toString(),
      
      date: json['date']?.toString(),
      bmrKcal: json['bmr_kcal'] as num?,
      tdeeKcal: json['tdee_kcal'] as num?,
      target: json['target'] != null ? NutritionMacroModel.fromJson(json['target']) : null,
      consumed: json['consumed'] != null ? NutritionMacroModel.fromJson(json['consumed']) : null,
      remaining: json['remaining'] != null ? NutritionMacroModel.fromJson(json['remaining']) : null,
    );
  }

  // 👇 TECH LEAD TRICK: Hàm toJson() này cực kỳ quan trọng!
  // Khi bạn gọi API "PUT /profile" để cập nhật, bạn chỉ gửi đi các trường Nhóm 1.
  // Không gửi các trường Nhóm 2 (bmr, tdee...) vì đó là việc của Backend tự tính toán lại.
  Map<String, dynamic> toJson() {
    return {
      if (age != null) 'age': age,
      if (gender != null) 'gender': gender,
      if (heightCm != null) 'height_cm': heightCm,
      if (weightKg != null) 'weight_kg': weightKg,
      if (activityLevel != null) 'activity_level': activityLevel,
      if (goal != null) 'goal': goal,
    };
  }

  @override
  String toString() {
    return 'NutritionProfileModel(age: $age, gender: $gender, heightCm: $heightCm, weightKg: $weightKg, activityLevel: $activityLevel, goal: $goal, date: $date, bmrKcal: $bmrKcal, tdeeKcal: $tdeeKcal, target: $target, consumed: $consumed, remaining: $remaining)'; }
}