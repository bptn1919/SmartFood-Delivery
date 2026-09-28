//import 'dart:convert';

enum PaymentMethod { COD, MOMO }
enum OrderStatus { DRAFT, PENDING, CONFIRMED, PROCESSING, DELIVERING, COMPLETED, CANCELLED }

PaymentMethod _pmFrom(String? s) {
  switch ((s ?? '').toUpperCase()) {
    case 'COD':  return PaymentMethod.COD;
    case 'MOMO': return PaymentMethod.MOMO;
    default:     return PaymentMethod.COD;
  }
}

OrderStatus _stFrom(String? s) {
  switch ((s ?? '').toUpperCase()) {
    case 'DRAFT':       return OrderStatus.DRAFT;
    case 'PENDING':     return OrderStatus.PENDING;
    case 'CONFIRMED':   return OrderStatus.CONFIRMED;
    case 'PROCESSING':  return OrderStatus.PROCESSING;
    case 'DELIVERING':  return OrderStatus.DELIVERING;
    case 'COMPLETED':   return OrderStatus.COMPLETED;
    case 'CANCELLED':   return OrderStatus.CANCELLED;
    default:            return OrderStatus.DRAFT;
  }
}

double _numOrStringToDouble(dynamic v) {
  if (v == null) return 0;
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v) ?? 0;
  return 0;
}

class OrderLineItem {
  final String dishUid;
  final String dishName;
  final String? imageUrl;
  final int quantity;
  final double price;
  final double subtotal;

  const OrderLineItem({
    required this.dishUid,
    required this.dishName,
    required this.quantity,
    required this.price,
    required this.subtotal,
    this.imageUrl,
  });

  factory OrderLineItem.fromJson(Map<String, dynamic> json) => OrderLineItem(
        dishUid: json['dish_uid']?.toString() ?? '',
        dishName: json['dish_name']?.toString() ?? '',
        imageUrl: (json['image_url']?.toString().isEmpty ?? true) ? null : json['image_url'].toString(),
        quantity: (json['quantity'] as num?)?.toInt() ?? 0,
        price: _numOrStringToDouble(json['price']),
        subtotal: _numOrStringToDouble(json['subtotal']),
      );
}

class CustomerOrder {
  final String uid;
  final int chefId;
  final String chefName;

  final double subTotal;
  final double taxAndFees;
  final double deliveryFee;
  final double totalPrice;

  final List<OrderLineItem> items;

  final String fullName;
  final String phoneNumber;
  final String deliveryDate; // yyyy-MM-dd
  final String deliveryTime; // HH:mm:ss
  final String? deliveryAddress;

  final PaymentMethod paymentMethod;
  final OrderStatus status;

  const CustomerOrder({
    required this.uid,
    required this.chefId,
    required this.chefName,
    required this.subTotal,
    required this.taxAndFees,
    required this.deliveryFee,
    required this.totalPrice,
    required this.items,
    required this.fullName,
    required this.phoneNumber,
    required this.deliveryDate,
    required this.deliveryTime,
    required this.deliveryAddress,
    required this.paymentMethod,
    required this.status,
  });

  int get totalQuantity => items.fold(0, (s, e) => s + e.quantity);

  factory CustomerOrder.fromJson(Map<String, dynamic> json) {
    final rawItems = (json['items'] as List? ?? const []);
    return CustomerOrder(
      uid: json['uid']?.toString() ?? '',
      chefId: (json['chef_id'] as num?)?.toInt() ?? 0,
      chefName: json['chef_name']?.toString() ?? 'Unknown Chef',
      subTotal: _numOrStringToDouble(json['sub_total']),
      taxAndFees: _numOrStringToDouble(json['tax_and_fees']),
      deliveryFee: _numOrStringToDouble(json['delivery_fee']),
      totalPrice: _numOrStringToDouble(json['total_price']),
      items: rawItems.map((e) => OrderLineItem.fromJson(Map<String, dynamic>.from(e))).toList(),
      fullName: json['full_name']?.toString() ?? '',
      phoneNumber: json['phone_number']?.toString() ?? '',
      deliveryDate: json['delivery_date']?.toString() ?? '',
      deliveryTime: json['delivery_time']?.toString() ?? '',
      deliveryAddress: json['delivery_address']?.toString(),
      paymentMethod: _pmFrom(json['payment_method']?.toString()),
      status: _stFrom(json['status']?.toString()),
    );
  }
}
