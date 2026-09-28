class ChefModel {
  final int userId;
  final String fullname;
  final String email;
  final String phone;
  final String bio;
  final String specialty; // Thêm trường specialty
  final String imageUrl; // map từ field 'file'
  final double rating;
  final int numberOfOrders;
  final bool isFoodSafetyCertified;

  ChefModel({
    required this.userId,
    required this.fullname,
    required this.email,
    required this.phone,
    required this.bio,
    required this.specialty, // Thêm vào constructor
    required this.imageUrl,
    required this.rating,
    required this.numberOfOrders,
    this.isFoodSafetyCertified = false,
  });

  factory ChefModel.fromJson(Map<String, dynamic> json) {
    return ChefModel(
      userId: (json['user_id'] as num?)?.toInt() ?? 0,
      fullname: json['fullname'] ?? '',
      email: json['mail'] ?? '',
      phone: json['phone'] ?? '',
      bio: json['bio'] ?? '',
      specialty: json['specialty'] ?? '', // Thêm dòng này
      // Field 'file' chứa link ảnh (avatar)
      imageUrl: json['file'] ?? json['avatar'] ?? '', // Thêm fallback cho avatar field
      rating: (json['rating'] as num?)?.toDouble() ?? 0.0,
      numberOfOrders: (json['number_of_orders'] as num?)?.toInt() ?? 0,
      isFoodSafetyCertified: json['is_food_safety_certified'] ?? false,
    );
  }
}