// cart_summary.dart
import 'cart_item.dart';

class CartSummary {
  final List<CartItemModel> items;
  final double totalAmount;
  final String message;

  const CartSummary({
    required this.items,
    required this.totalAmount,
    required this.message,
  });

  /// Hỗ trợ 2 kiểu body:
  /// 1) Phẳng: { items: [ {uid, dish_uid, ...} ], total_amount, message }
  /// 2) Grouped theo ngày/chef (Swagger sample):
  ///    { items: [ { delivery_date, chefs: [ { chef_name, items:[ {...} ] } ] } ], total_amount, message }
  factory CartSummary.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    final flattened = <CartItemModel>[];

    if (rawItems is List) {
      // Thử parse kiểu phẳng trước
      bool parsedFlat = false;
      try {
        for (final e in rawItems) {
          final m = Map<String, dynamic>.from(e as Map);
          if (m.containsKey('dish_uid')) {
            flattened.add(CartItemModel.fromJson(m));
            parsedFlat = true;
          }
        }
      } catch (_) {
        parsedFlat = false;
      }

      if (!parsedFlat) {
        // Thử kiểu grouped (delivery_date -> chefs -> items[])
        for (final g in rawItems) {
          final group = Map<String, dynamic>.from(g as Map);
          final deliveryDate = group['delivery_date'];
          final chefs = group['chefs'] as List? ?? const [];
          for (final c in chefs) {
            final chef = Map<String, dynamic>.from(c as Map);
            final items = chef['items'] as List? ?? const [];
            for (final it in items) {
              final m = Map<String, dynamic>.from(it as Map);
              // nếu item thiếu delivery_date, gán từ group
              m.putIfAbsent('delivery_date', () => deliveryDate);
              flattened.add(CartItemModel.fromJson(m));
            }
          }
        }
      }
    }

    final total = (json['total_amount'] is num)
        ? (json['total_amount'] as num).toDouble()
        : 0.0;

    return CartSummary(
      items: flattened,
      totalAmount: total,
      message: json['message']?.toString() ?? '',
    );
  }

  

  Map<String, dynamic> toJson() => {
        'items': items.map((e) => e.toJson()).toList(),
        'total_amount': totalAmount,
        'message': message,
      };

  CartSummary copyWith({
    List<CartItemModel>? items,
    double? totalAmount,
    String? message,
  }) {
    return CartSummary(
      items: items ?? this.items,
      totalAmount: totalAmount ?? this.totalAmount,
      message: message ?? this.message,
    );
  }
}
