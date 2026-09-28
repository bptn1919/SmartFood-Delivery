class SettleCodResponse {
  final bool success;
  final int chefId;
  final String chefEmail;
  final double settledAmount;
  final int orderCount;
  final String settledAt;
  final String message;
  final String createdAt;
  final List<dynamic> transactions;
  final String error;

  SettleCodResponse({
    required this.success,
    required this.chefId,
    required this.chefEmail,
    required this.settledAmount,
    required this.orderCount,
    required this.settledAt,
    required this.message,
    required this.createdAt,
    required this.transactions,
    required this.error,
  });

  factory SettleCodResponse.fromJson(Map<String, dynamic> json) {
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return SettleCodResponse(
      success: json['success'] ?? false,
      chefId: json['chef_id'] ?? 0,
      chefEmail: json['chef_email'] ?? '',
      settledAmount: parseDouble(json['settled_amount']),
      orderCount: json['order_count'] ?? 0,
      settledAt: json['settled_at'] ?? '',
      message: json['message'] ?? '',
      createdAt: json['created_at'] ?? '',
      transactions: json['transactions'] ?? [],
      error: json['error'] ?? '',
    );
  }
}