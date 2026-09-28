class DishAvailabilityModel {
  final DateTime date;
  final int quantity;
  final bool isAvailable;

  DishAvailabilityModel({
    required this.date,
    required this.quantity,
    required this.isAvailable,
  });

  factory DishAvailabilityModel.fromJson(Map<String, dynamic> json) {
    return DishAvailabilityModel(
      date: DateTime.tryParse(json['available_date'] ?? '') ?? DateTime.now(),
      quantity: (json['available_quantity'] as num?)?.toInt() ?? 0,
      isAvailable: json['is_available'] ?? true,
    );
  }
}  