class WithdrawResponse {
  final bool success;
  final String message;
  final double amount;
  final String status;
  final String payoutId;
  final String referenceId;
  final String bankAccount;
  final String error;

  WithdrawResponse({
    required this.success,
    required this.message,
    required this.amount,
    required this.status,
    required this.payoutId,
    required this.referenceId,
    required this.bankAccount,
    required this.error,
  });

  factory WithdrawResponse.fromJson(Map<String, dynamic> json) {
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return WithdrawResponse(
      success: json['success'] ?? false,
      message: json['message'] ?? '',
      amount: parseDouble(json['amount']),
      status: json['status'] ?? '',
      payoutId: json['payout_id'] ?? '',
      referenceId: json['reference_id'] ?? '',
      bankAccount: json['bank_account'] ?? '',
      error: json['error'] ?? '',
    );
  }
}