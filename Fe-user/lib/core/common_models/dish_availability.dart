class DishAvailability {
  final DateTime date;
  final int availableQuantity;
  final String? note;

  DishAvailability({
    required this.date,
    required this.availableQuantity,
    this.note,
  });

  factory DishAvailability.fromJson(Map<String, dynamic> json) {
    // BE dùng 'available_date' và 'available_quantity'
    return DishAvailability(
      date: DateTime.parse(json['available_date'] as String),
      availableQuantity: (json['available_quantity'] as num).toInt(),
      note: json['note'] as String?,
    );
  }
}
