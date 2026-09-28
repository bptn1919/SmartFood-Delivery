import 'cart_item_model.dart';

class CartSummary {
  final List<CartItemModel> items;
  final double totalAmount;
  final String message;

  const CartSummary({
    required this.items,
    required this.totalAmount,
    required this.message,
  });

  factory CartSummary.fromJson(Map<String, dynamic> json) {
    // Repository chịu trách nhiệm truyền vào 'items' là List<CartItemModel> chuẩn
    // hoặc json['items'] đã là flat list.
    
    var list = <CartItemModel>[];
    if (json['items'] != null && json['items'] is List) {
      list = (json['items'] as List)
          .map((e) => CartItemModel.fromJson(Map<String, dynamic>.from(e)))
          .toList();
    }

    final total = (json['total_amount'] is num)
        ? (json['total_amount'] as num).toDouble()
        : 0.0;

    return CartSummary(
      items: list,
      totalAmount: total,
      message: json['message']?.toString() ?? '',
    );
  }
}