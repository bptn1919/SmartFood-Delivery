import 'customer_order.dart';
// 👆 Import để dùng chung: OrderLineItem, PaymentMethod, OrderStatus

// Helper private (có thể tách ra file utils chung nếu muốn)
double _parseNum(dynamic v) {
  if (v == null) return 0.0;
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v) ?? 0.0;
  return 0.0;
}

class OrderDetail {
  final String uid;

  // Thông tin chef
  final int chefId;
  final String chefName;

  // Tiền
  final double subTotal;
  final double taxAndFees;
  final double deliveryFee;
  final double totalPrice;

  // Items trong order
  final List<OrderLineItem> items;

  // Thông tin khách + giao hàng
  final String fullName;
  final String phoneNumber;
  final String deliveryDate; // yyyy-MM-dd
  final String deliveryTime; // HH:mm:ss
  final String? deliveryAddress;
  final String deliveryType;
  final String? chefAddress;
  final double? chefLatitude;
  final double? chefLongitude;

  final double? deliveryLatitude;
  final double? deliveryLongitude;

  final String? trackingCode;
  final String? trackingLink;

  // Thanh toán & trạng thái (Dùng Enum thay vì String)
  final PaymentMethod paymentMethod;
  final OrderStatus status;

  const OrderDetail({
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
    required this.deliveryType,
    required this.chefAddress,
    required this.chefLatitude,
    required this.chefLongitude,
    required this.deliveryLatitude, // Bổ sung vào constructor
    required this.deliveryLongitude,
    this.trackingCode,
    this.trackingLink,
    required this.paymentMethod,
    required this.status,
  });

  /// Tổng số lượng món trong order
  int get totalQuantity => items.fold(0, (sum, item) => sum + item.quantity);

  factory OrderDetail.fromJson(Map<String, dynamic> json) {
    final rawItems = (json['items'] as List? ?? const []);

    return OrderDetail(
      uid: json['uid']?.toString() ?? '',

      chefId: (json['chef_id'] as num?)?.toInt() ?? 0,
      chefName: json['chef_name']?.toString() ?? 'Unknown Chef',

      subTotal: _parseNum(json['sub_total']),
      taxAndFees: _parseNum(json['tax_and_fees']),
      deliveryFee: _parseNum(json['delivery_fee']),
      totalPrice: _parseNum(json['total_price']),

      items: rawItems
          .map((e) => OrderLineItem.fromJson(Map<String, dynamic>.from(e)))
          .toList(),

      fullName: json['full_name']?.toString() ?? '',
      phoneNumber: json['phone_number']?.toString() ?? '',
      deliveryDate: json['delivery_date']?.toString() ?? '',
      deliveryTime: json['delivery_time']?.toString() ?? '',
      deliveryAddress: json['delivery_address']?.toString(),
      deliveryType: json['delivery_type'] ?? 'THIRD_PARTY',
      chefAddress: json['chef_address']?.toString(),
      chefLatitude: json['chef_latitude'] is num
          ? (json['chef_latitude'] as num).toDouble()
          : null,
      chefLongitude: json['chef_longitude'] is num
          ? (json['chef_longitude'] as num).toDouble()
          : null,

      deliveryLatitude: json['delivery_latitude'] is num
          ? (json['delivery_latitude'] as num).toDouble()
          : null,
      deliveryLongitude: json['delivery_longitude'] is num
          ? (json['delivery_longitude'] as num).toDouble()
          : null,

      trackingCode: json['tracking_code']?.toString(),
      trackingLink: json['tracking_link']?.toString(),

      // Sử dụng helper fromString của Enum
      paymentMethod:
          PaymentMethod.fromString(json['payment_method']?.toString()),
      status: OrderStatus.fromString(json['status']?.toString()),
    );
  }

  OrderDetail copyWith({
    String? uid,
    int? chefId,
    String? chefName,
    double? subTotal,
    double? taxAndFees,
    double? deliveryFee,
    double? totalPrice,
    List<OrderLineItem>? items,
    String? fullName,
    String? phoneNumber,
    String? deliveryDate,
    String? deliveryTime,
    String? deliveryAddress,
    String? deliveryType,
    String? chefAddress,
    double? chefLatitude,
    double? chefLongitude,
    double? deliveryLatitude,
    double? deliveryLongitude,
    String? trackingCode,
    String? trackingLink,
    PaymentMethod? paymentMethod,
    OrderStatus? status,
  }) {
    return OrderDetail(
      uid: uid ?? this.uid,
      chefId: chefId ?? this.chefId,
      chefName: chefName ?? this.chefName,
      subTotal: subTotal ?? this.subTotal,
      taxAndFees: taxAndFees ?? this.taxAndFees,
      deliveryFee: deliveryFee ?? this.deliveryFee,
      totalPrice: totalPrice ?? this.totalPrice,
      items: items ?? this.items,
      fullName: fullName ?? this.fullName,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      deliveryDate: deliveryDate ?? this.deliveryDate,
      deliveryTime: deliveryTime ?? this.deliveryTime,
      deliveryAddress: deliveryAddress ?? this.deliveryAddress,
      deliveryType: deliveryType ?? this.deliveryType,
      chefAddress: chefAddress ?? this.chefAddress,
      chefLatitude: chefLatitude ?? this.chefLatitude,
      chefLongitude: chefLongitude ?? this.chefLongitude,
      deliveryLatitude: deliveryLatitude ?? this.deliveryLatitude,
      deliveryLongitude: deliveryLongitude ?? this.deliveryLongitude,
      trackingCode: trackingCode ?? this.trackingCode,
      trackingLink: trackingLink ?? this.trackingLink,
      paymentMethod: paymentMethod ?? this.paymentMethod,
      status: status ?? this.status,
    );
  }
}
