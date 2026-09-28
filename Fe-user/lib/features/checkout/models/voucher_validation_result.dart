class VoucherValidationResult {
  final bool isValid;
  final String message;
  final double discountAmount;
  final double finalAmount;

  VoucherValidationResult({
    required this.isValid,
    required this.message,
    required this.discountAmount,
    required this.finalAmount,
  });

  factory VoucherValidationResult.fromJson(Map<String, dynamic> json) {
    return VoucherValidationResult(
      isValid: json['is_valid'] ?? false,
      message: json['message'] ?? "",
      discountAmount: double.tryParse(json['discount_amount'].toString()) ?? 0.0,
      finalAmount: double.tryParse(json['final_amount'].toString()) ?? 0.0,
    );
  }
}