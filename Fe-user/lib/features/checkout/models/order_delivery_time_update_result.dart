class OrderDeliveryTimeUpdateResult {
  final String deliveryDate;
  final String deliveryTime;

  const OrderDeliveryTimeUpdateResult({
    required this.deliveryDate,
    required this.deliveryTime,
  });

  factory OrderDeliveryTimeUpdateResult.fromJson(
    Map<String, dynamic> json, {
    required String fallbackDeliveryDate,
    required String fallbackDeliveryTime,
  }) {
    return OrderDeliveryTimeUpdateResult(
      deliveryDate: json['delivery_date']?.toString() ?? fallbackDeliveryDate,
      deliveryTime: json['delivery_time']?.toString() ?? fallbackDeliveryTime,
    );
  }
}
