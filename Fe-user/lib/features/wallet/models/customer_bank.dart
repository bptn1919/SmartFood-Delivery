class CustomerBank {
  final String bankName;
  final String bankCode;
  final String accountNumber;
  final String accountName;
  final String bankBranch;
  final bool isVerify;
  final String? verifyAt;
  final String? createdAt;
  final String? updatedAt;

  CustomerBank({
    required this.bankName,
    required this.bankCode,
    required this.accountNumber,
    required this.accountName,
    required this.bankBranch,
    required this.isVerify,
    this.verifyAt,
    this.createdAt,
    this.updatedAt,
  });

  factory CustomerBank.fromJson(Map<String, dynamic> json) {
    return CustomerBank(
      // Bọc fallback '' để chống lỗi null
      bankName: json['bank_name'] ?? '',
      bankCode: json['bank_code'] ?? '',
      
      // TECH LEAD FIX: Sửa key cho khớp log API
      accountNumber: json['bank_account_number'] ?? '', 
      accountName: json['bank_account_name'] ?? '',
      
      // TECH LEAD FIX: bank_branch có thể null từ API, nên trả về chuỗi rỗng
      bankBranch: json['bank_branch'] ?? '', 
      
      // TECH LEAD FIX: Sửa key cho khớp log API
      isVerify: json['is_verified'] ?? false, 
      verifyAt: json['verified_at'], 
      
      createdAt: json['created_at'],
      updatedAt: json['updated_at'],
    );
  }

  // Luôn giữ hàm toJson để dùng cho API Upsert lúc trước nhé
  Map<String, dynamic> toUpsertJson() {
    return {
      'bank_name': bankName,
      'bank_code': bankCode,
      'bank_account_number': accountNumber,
      'bank_account_name': accountName,
      'bank_branch': bankBranch.isEmpty ? null : bankBranch,
    };
  }
}