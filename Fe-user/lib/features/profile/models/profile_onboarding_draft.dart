class ProfileOnboardingDraft {
  final double? heightCm;
  final double? weightKg;
  final List<String> allergicIngredientUids;
  final List<String> favoriteIngredientUids;

  const ProfileOnboardingDraft({
    this.heightCm,
    this.weightKg,
    this.allergicIngredientUids = const [],
    this.favoriteIngredientUids = const [],
  });

  ProfileOnboardingDraft copyWith({
    double? heightCm,
    double? weightKg,
    List<String>? allergicIngredientUids,
    List<String>? favoriteIngredientUids,
  }) {
    return ProfileOnboardingDraft(
      heightCm: heightCm ?? this.heightCm,
      weightKg: weightKg ?? this.weightKg,
      allergicIngredientUids:
          allergicIngredientUids ?? this.allergicIngredientUids,
      favoriteIngredientUids:
          favoriteIngredientUids ?? this.favoriteIngredientUids,
    );
  }

  Map<String, dynamic> toPayloadJson() {
    return {
      'height_cm': heightCm,
      'weight_kg': weightKg,
      'allergic_ingredient_uids': allergicIngredientUids,
      'favorite_ingredient_uids': favoriteIngredientUids,
    };
  }
}
