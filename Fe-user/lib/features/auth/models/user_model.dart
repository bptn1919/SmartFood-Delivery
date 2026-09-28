// features/auth/models/user_model.dart
class UserModel {
  final String username;
  final String email;
  final String? fullname; // Thêm fullname từ API
  final bool? isChef; // Thêm trường isChef
  final int? chefId; // Thêm chefId
  final bool isOnboarded;

  UserModel({
    required this.username,
    required this.email,
    this.fullname,
    this.isChef,
    this.chefId,
    this.isOnboarded = false,
  });

  // Factory parse từ JSON response của Login/GetMe
  factory UserModel.fromJson(Map<String, dynamic> json) {
    return UserModel(
      username: json['username'] ?? '',
      email: json['email'] ?? '',
      fullname: json['full_name'] ?? json['fullname'],
      isChef: json['is_chef'] ?? json['isChef'],
      chefId: json['chef_id'] ?? json['chefId'],
      isOnboarded: json['is_onboarded'] ?? json['isOnboarded'] ?? false,
    );
  }

  // Helper method để kiểm tra role
  bool get isChefUser => isChef == true;

  // CopyWith method để dễ dàng cập nhật
  UserModel copyWith({
    String? username,
    String? email,
    String? fullname,
    bool? isChef,
    int? chefId,
    bool? isOnboarded,
  }) {
    return UserModel(
      username: username ?? this.username,
      email: email ?? this.email,
      fullname: fullname ?? this.fullname,
      isChef: isChef ?? this.isChef,
      chefId: chefId ?? this.chefId,
      isOnboarded: isOnboarded ?? this.isOnboarded,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'username': username,
      'email': email,
      'fullname': fullname,
      'is_chef': isChef,
      'chef_id': chefId,
      'is_onboarded': isOnboarded,
    };
  }
}
