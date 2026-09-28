//import 'package:flutter/foundation.dart';

class CartItemModel {
  final String uid;          // uid của cart_item
  final String dishUid;
  final String dishName;
  final String chefName;     // <— NEW
  final double price;
  final int quantity;
  final DateTime deliveryDate;
  final bool isSelected;

  const CartItemModel({
    required this.uid,
    required this.dishUid,
    required this.dishName,
    required this.chefName,       // <— NEW
    required this.price,
    required this.quantity,
    required this.deliveryDate,
    required this.isSelected,
  });

  CartItemModel copyWith({
    String? uid,
    String? dishUid,
    String? dishName,
    String? chefName,             // <— NEW
    double? price,
    int? quantity,
    DateTime? deliveryDate,
    bool? isSelected,
  }) {
    return CartItemModel(
      uid: uid ?? this.uid,
      dishUid: dishUid ?? this.dishUid,
      dishName: dishName ?? this.dishName,
      chefName: chefName ?? this.chefName,      // <— NEW
      price: price ?? this.price,
      quantity: quantity ?? this.quantity,
      deliveryDate: deliveryDate ?? this.deliveryDate,
      isSelected: isSelected ?? this.isSelected,
    );
  }

  factory CartItemModel.fromJson(Map<String, dynamic> json) {
    final sel = json['is_selected'];
    final boolSelected = (sel == true || sel == 1 || sel == '1');

    // chấp nhận cả 'chef_name' và 'chefName'
    final chef = (json['chef_name'] ?? json['chefName'] ?? '').toString();

    return CartItemModel(
      uid: json['uid']?.toString() ?? '',
      dishUid: json['dish_uid']?.toString() ?? '',
      dishName: json['dish_name']?.toString() ?? '',
      chefName: chef,                                // <— NEW
      price: (json['price'] is num) ? (json['price'] as num).toDouble() : 0.0,
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      deliveryDate: DateTime.tryParse(json['delivery_date']?.toString() ?? '') ?? DateTime.now(),
      isSelected: boolSelected,
    );
  }

  Map<String, dynamic> toJson() => {
        'uid': uid,
        'dish_uid': dishUid,
        'dish_name': dishName,
        'chef_name': chefName,   // <— NEW (giữ đúng key BE)
        'price': price,
        'quantity': quantity,
        'delivery_date':
            '${deliveryDate.year}-${deliveryDate.month.toString().padLeft(2, '0')}-${deliveryDate.day.toString().padLeft(2, '0')}',
        'is_selected': isSelected,
      };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CartItemModel && runtimeType == other.runtimeType && uid == other.uid;

  @override
  int get hashCode => uid.hashCode;

  @override
  String toString() =>
      'CartItemModel(uid=$uid, chef=$chefName, dish=$dishName, qty=$quantity, selected=$isSelected)';
}
