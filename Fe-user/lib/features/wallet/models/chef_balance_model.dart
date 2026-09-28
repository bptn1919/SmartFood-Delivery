class CodBalance {
  final double unsettledBalance;
  final int unsettledOrders;
  final String note;

  CodBalance({
    required this.unsettledBalance,
    required this.unsettledOrders,
    required this.note,
  });

  factory CodBalance.fromJson(Map<String, dynamic> json) {
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return CodBalance(
      unsettledBalance: parseDouble(json['unsettled_balance']),
      unsettledOrders: json['unsettled_orders'] ?? 0,
      note: json['note'] ?? '',
    );
  }
}

class PayosBalance {
  final double pendingPayout;
  final String note;

  PayosBalance({
    required this.pendingPayout,
    required this.note,
  });

  factory PayosBalance.fromJson(Map<String, dynamic> json) {
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return PayosBalance(
      pendingPayout: parseDouble(json['pending_payout']),
      note: json['note'] ?? '',
    );
  }
}

class ChefBalanceModel {
  final int chefId;
  final String chefEmail;
  final CodBalance codBalance;
  final PayosBalance payosBalance;
  final double totalAvailablePayout;
  final double totalSettled;
  final String currency;

  ChefBalanceModel({
    required this.chefId,
    required this.chefEmail,
    required this.codBalance,
    required this.payosBalance,
    required this.totalAvailablePayout,
    required this.totalSettled,
    required this.currency,
  });

  factory ChefBalanceModel.fromJson(Map<String, dynamic> json) {
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return ChefBalanceModel(
      chefId: json['chef_id'] ?? 0,
      chefEmail: json['chef_email'] ?? '',
      codBalance: CodBalance.fromJson(json['cod_balance'] ?? {}),
      payosBalance: PayosBalance.fromJson(json['payos_balance'] ?? {}),
      totalAvailablePayout: parseDouble(json['total_available_payout']),
      totalSettled: parseDouble(json['total_settled']),
      currency: json['currency'] ?? 'VND',
    );
  }
}