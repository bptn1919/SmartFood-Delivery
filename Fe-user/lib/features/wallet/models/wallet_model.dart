class WalletModel {
  final String walletUid;
  final int userId;
  final double balance;
  final double pendingBalance;
  final double totalBalance;
  final String currency;
  final List<dynamic> recentTransactions; // Có thể tạo model riêng cho Transaction sau

  WalletModel({
    required this.walletUid,
    required this.userId,
    required this.balance,
    required this.pendingBalance,
    required this.totalBalance,
    required this.currency,
    required this.recentTransactions,
  });

  factory WalletModel.fromJson(Map<String, dynamic> json) {
    // Helper method to safely convert any dynamic value (String, int, double, null) to double
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble(); // Handles both int and double
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return WalletModel(
      walletUid: json['wallet_uid'] ?? '',
      userId: json['user_id'] ?? 0,
      balance: parseDouble(json['balance']),
      pendingBalance: parseDouble(json['pending_balance']),
      totalBalance: parseDouble(json['total_balance']),
      currency: json['currency'] ?? 'VND',
      recentTransactions: json['recent_transactions'] ?? [],
    );
  }
}