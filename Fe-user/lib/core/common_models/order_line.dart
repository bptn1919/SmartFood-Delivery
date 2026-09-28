class OrderDraftLine {
  final String dishUid;
  final String dishName;
  final String? imageUrl;
  final double price;
  final int quantity;
  final double lineTotal;
  final int? chefId;       // lấy từ orders[].chef_id khi checkout
  final String? chefName;  // lấy từ orders[].chef_name khi checkout

  OrderDraftLine({
    required this.dishUid,
    required this.dishName,
    this.imageUrl,
    required this.quantity,
    required this.price,
    required this.lineTotal,
    this.chefId,
    this.chefName,
  });

  /// ✅ Tạo object từ JSON (hỗ trợ cả 'subtotal' và 'line_total')
  factory OrderDraftLine.fromJson(Map<String, dynamic> json) {
    final double parsedPrice = (json['price'] is num)
        ? (json['price'] as num).toDouble()
        : double.tryParse('${json['price']}') ?? 0;

    final int parsedQuantity = int.tryParse('${json['quantity']}') ??
        (json['quantity'] as num?)?.toInt() ??
        0;

    final double parsedLineTotal = (json['line_total'] is num)
        ? (json['line_total'] as num).toDouble()
        : (json['subtotal'] is num)
            ? (json['subtotal'] as num).toDouble()
            : double.tryParse('${json['line_total'] ?? json['subtotal']}') ??
                (parsedPrice * parsedQuantity);

    return OrderDraftLine(
      dishUid: (json['dish_uid'] ?? '').toString(),
      dishName: (json['dish_name'] ?? '').toString(),
      imageUrl: json['image_url']?.toString(),
      price: parsedPrice,
      quantity: parsedQuantity,
      lineTotal: parsedLineTotal,
      chefId: (json['chef_id'] as num?)?.toInt(),
      chefName: json['chef_name']?.toString(),
    );
  }

  /// ✅ Đưa object về JSON
  Map<String, dynamic> toJson() => {
        'dish_uid': dishUid,
        'dish_name': dishName,
        'image_url': imageUrl,
        'price': price,
        'quantity': quantity,
        'line_total': lineTotal,
        if (chefId != null) 'chef_id': chefId,
        if (chefName != null) 'chef_name': chefName,
      };

  /// ✅ Hỗ trợ cập nhật 1 vài trường mà không cần tạo mới toàn bộ
  OrderDraftLine copyWith({
    String? dishUid,
    String? dishName,
    String? imageUrl,
    double? price,
    int? quantity,
    double? lineTotal,
    int? chefId,
    String? chefName,
  }) {
    final double newPrice = price ?? this.price;
    final int newQuantity = quantity ?? this.quantity;

    return OrderDraftLine(
      dishUid: dishUid ?? this.dishUid,
      dishName: dishName ?? this.dishName,
      imageUrl: imageUrl ?? this.imageUrl,
      price: newPrice,
      quantity: newQuantity,
      lineTotal: lineTotal ?? (newPrice * newQuantity),
      chefId: chefId ?? this.chefId,
      chefName: chefName ?? this.chefName,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is OrderDraftLine &&
          runtimeType == other.runtimeType &&
          dishUid == other.dishUid &&
          dishName == other.dishName &&
          imageUrl == other.imageUrl &&
          price == other.price &&
          quantity == other.quantity &&
          lineTotal == other.lineTotal &&
          chefId == other.chefId &&
          chefName == other.chefName;

  @override
  int get hashCode =>
      dishUid.hashCode ^
      dishName.hashCode ^
      (imageUrl?.hashCode ?? 0) ^
      price.hashCode ^
      quantity.hashCode ^
      lineTotal.hashCode ^
      (chefId?.hashCode ?? 0) ^
      (chefName?.hashCode ?? 0);

  @override
  String toString() {
    return 'OrderDraftLine(dishUid: $dishUid, dishName: $dishName, price: $price, '
        'quantity: $quantity, lineTotal: $lineTotal, imageUrl: $imageUrl, '
        'chefId: $chefId, chefName: $chefName)';
  }
}
