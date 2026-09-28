class ChefProfileModel {
  final int id;
  final String? fullname;
  final String? phone;
  final String? avatar;
  final String? mail;
  final String? bio;
  final String? specialty;
  final double rating;
  final int numberOfOrders;
  final bool isFoodSafetyCertified;
  final BankModel? bank;

  final String? kitchenAddress;
  final String? kitchenStreet;
  final String? kitchenWard;
  final String? kitchenDistrict;
  final String? kitchenCity;
  final double? kitchenLatitude;
  final double? kitchenLongitude;

  ChefProfileModel({
    required this.id,
    this.fullname,
    this.phone,
    this.avatar,
    this.mail,
    this.bio,
    this.specialty,
    this.rating = 0.0,
    this.numberOfOrders = 0,
    this.isFoodSafetyCertified = false,
    this.bank,

    this.kitchenAddress,
    this.kitchenStreet,
    this.kitchenWard,
    this.kitchenDistrict,
    this.kitchenCity,
    this.kitchenLatitude,
    this.kitchenLongitude,
  });

  factory ChefProfileModel.fromJson(Map<String, dynamic> json) {
    return ChefProfileModel(
      // Bọc thép kiểm tra kiểu dữ liệu chống crash trên Web
      id: json['user_id'] is num ? (json['user_id'] as num).toInt() : 0,
      fullname: json['fullname'] is String ? json['fullname'] : null,
      phone: json['phone'] is String ? json['phone'] : null,
      avatar: json['avatar'] is String ? json['avatar'] : null,
      mail: json['mail'] is String ? json['mail'] : null,
      bio: json['bio'] is String ? json['bio'] : null,
      specialty: json['specialty'] is String ? json['specialty'] : null,
      rating: json['rating'] is num ? (json['rating'] as num).toDouble() : 0.0,
      numberOfOrders: json['number_of_orders'] is num ? (json['number_of_orders'] as num).toInt() : 0,
      isFoodSafetyCertified: json['is_food_safety_certified'] ?? false,
      bank: json['bank'] != null ? BankModel.fromJson(json['bank']) : null,

      kitchenAddress: json['kitchen_address'] is String ? json['kitchen_address'] : null,
      kitchenStreet: json['kitchen_street'] is String ? json['kitchen_street'] : null,
      kitchenWard: json['kitchen_ward'] is String ? json['kitchen_ward'] : null,
      kitchenDistrict: json['kitchen_district'] is String ? json['kitchen_district'] : null,
      kitchenCity: json['kitchen_city'] is String ? json['kitchen_city'] : null,
      // Ép kiểu double cẩn thận để chống crash
      kitchenLatitude: json['kitchen_latitude'] is num ? (json['kitchen_latitude'] as num).toDouble() : null,
      kitchenLongitude: json['kitchen_longitude'] is num ? (json['kitchen_longitude'] as num).toDouble() : null,
    );
  }
}

class BankModel {
  final String? bankName;
  final String? bankCode;
  final String? bankAccountNumber;
  final String? bankAccountName;
  final String? bankBranch;
  final bool isVerified;

  BankModel({
    this.bankName,
    this.bankCode,
    this.bankAccountNumber,
    this.bankAccountName,
    this.bankBranch,
    this.isVerified = false,
  });

  factory BankModel.fromJson(Map<String, dynamic> json) {
    return BankModel(
      bankName: json['bank_name'] is String ? json['bank_name'] : null,
      bankCode: json['bank_code'] is String ? json['bank_code'] : null,
      bankAccountNumber: json['bank_account_number'] is String ? json['bank_account_number'] : null,
      bankAccountName: json['bank_account_name'] is String ? json['bank_account_name'] : null,
      bankBranch: json['bank_branch'] is String ? json['bank_branch'] : null,
      isVerified: json['is_verified'] is bool ? json['is_verified'] : false,
    );
  }
}