class ChefPaymentModel {
  final String bankName;
  final String bankCode;
  final String bankAccountNumber;
  final String bankAccountName;
  final String? bankBranch;
  final bool isVerified;
  final String? verifiedAt;
  final String? createdAt;
  final String? updatedAt;

  ChefPaymentModel({
    required this.bankName,
    required this.bankCode,
    required this.bankAccountNumber,
    required this.bankAccountName,
    this.bankBranch,
    required this.isVerified,
    this.verifiedAt,
    this.createdAt,
    this.updatedAt,
  });

  factory ChefPaymentModel.fromJson(Map<String, dynamic> json) {
    return ChefPaymentModel(
      bankName: json['bank_name'] ?? '',
      bankCode: json['bank_code'] ?? '',
      bankAccountNumber: json['bank_account_number'] ?? '',
      bankAccountName: json['bank_account_name'] ?? '',
      bankBranch: json['bank_branch'],
      isVerified: json['is_verified'] ?? false,
      verifiedAt: json['verified_at'],
      createdAt: json['created_at'],
      updatedAt: json['updated_at'],
    );
  }
}