class PlaceOrderResponse {
  final String uid;
  final String paymentMethod;
  final double totalPrice;
  final String? paymentUrl;
  final String? paymentUid;
  final String? transactionId;

  PlaceOrderResponse({
    required this.uid,
    required this.paymentMethod,
    required this.totalPrice,
    this.paymentUrl,
    this.paymentUid,
    this.transactionId,
  });

  factory PlaceOrderResponse.fromJson(Map<String, dynamic> json) {
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    return PlaceOrderResponse(
      uid: json['uid'] ?? '',
      paymentMethod: json['payment_method'] ?? '',
      totalPrice: parseDouble(json['total_price']),
      // 💡 Hứng 3 trường thông tin thanh toán từ API
      paymentUrl: json['payment_url'],
      paymentUid: json['payment_uid'],
      transactionId: json['transaction_id'],
    );
  }
}