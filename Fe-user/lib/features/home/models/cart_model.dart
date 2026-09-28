class CartItemModel {
  final String uid;         // ID của dòng trong giỏ hàng (dùng để Toggle)
  final String dishUid;     // ID của món ăn (dùng để Update/Delete cùng với ngày)
  final String name;
  final String chefName;
  final double price;
  final int quantity;
  final String deliveryDate; // YYYY-MM-DD
  final bool isSelected;
  final String? imageUrl;

  CartItemModel({
    required this.uid,
    required this.dishUid,
    required this.name,
    required this.chefName,
    required this.price,
    required this.quantity,
    required this.deliveryDate,
    this.isSelected = true, // Mặc định là true nếu API không trả về
    this.imageUrl,
  });



  CartItemModel copyWith({
    String? uid,
    String? dishUid,
    String? dishName,
    String? chefName,             // <— NEW
    double? price,
    int? quantity,
    String? deliveryDate,
    bool? isSelected,
    String? imageUrl,
  }) {
    return CartItemModel(
      uid: uid ?? this.uid,
      dishUid: dishUid ?? this.dishUid,
      name: dishName ?? this.name,
      chefName: chefName ?? this.chefName,      // <— NEW
      price: price ?? this.price,
      quantity: quantity ?? this.quantity,
      deliveryDate: deliveryDate ?? this.deliveryDate,
      isSelected: isSelected ?? this.isSelected,
      imageUrl: imageUrl ?? this.imageUrl
    );
  }

  // Tính tổng tiền tạm tính của item này
  double get totalPrice => price * quantity;

  // Factory parse từ JSON (Xử lý Flattening sẽ làm ở Repository)
  factory CartItemModel.fromJson(Map<String, dynamic> json, {String? dateInfo, String? chefInfo}) {
    return CartItemModel(
      uid: json['uid'] ?? '',
      dishUid: json['dish_uid'] ?? '',
      name: json['dish_name'] ?? json['name'] ?? 'Unknown Dish',
      chefName: chefInfo ?? json['chef_name'] ?? '',
      price: (json['price'] as num?)?.toDouble() ?? 0.0,
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      deliveryDate: dateInfo ?? json['delivery_date'] ?? '',
      isSelected: json['is_selected'] ?? true,
      imageUrl: json['image_url'],
    );
  }
}

class CartSummaryModel {
  final List<CartItemModel> items;
  final double totalAmount;
  final String message;

  CartSummaryModel({
    required this.items,
    required this.totalAmount,
    this.message = '',
  });

  factory CartSummaryModel.empty() {
    return CartSummaryModel(items: [], totalAmount: 0);
  }
}