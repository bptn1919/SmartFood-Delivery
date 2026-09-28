class NearbyChefModel {
  final int chefId;
  final String chefName;
  final String? avatar;
  final double latitude;
  final double longitude;
  final double distanceKm;
  final double avgRating;
  final bool isFoodSafetyCertified;

  NearbyChefModel({
    required this.chefId,
    required this.chefName,
    this.avatar,
    required this.latitude,
    required this.longitude,
    required this.distanceKm,
    required this.avgRating,
    required this.isFoodSafetyCertified,
  });

  factory NearbyChefModel.fromJson(Map<String, dynamic> json) {
    return NearbyChefModel(
      chefId: json['chef_id'] ?? 0,
      chefName: json['chef_name'] ?? 'Unknown Chef',
      avatar: json['avatar'],
      // Dùng (as num).toDouble() để chống lỗi crash khi Backend trả về số nguyên (VD: 0 thay vì 0.0)
      latitude: (json['latitude'] as num?)?.toDouble() ?? 0.0,
      longitude: (json['longitude'] as num?)?.toDouble() ?? 0.0,
      distanceKm: (json['distance_km'] as num?)?.toDouble() ?? 0.0,
      avgRating: (json['avg_rating'] as num?)?.toDouble() ?? 0.0,
      isFoodSafetyCertified: json['is_food_safety_certified'] ?? false,
    );
  }
}