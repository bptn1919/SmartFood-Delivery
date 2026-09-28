// ignore_for_file: constant_identifier_names

import '../../../core/network/api_constants.dart'; // Import để lấy S3 URL

// ================= ENUMS =================

enum PaymentMethod {
  COD,
  MOMO,
  PAYOS;

  // Helper parse từ String
  static PaymentMethod fromString(String? value) {
    if (value == null) return PaymentMethod.COD;
    try {
      return PaymentMethod.values.firstWhere(
          (e) => e.name == value.toUpperCase(),
          orElse: () => PaymentMethod.COD);
    } catch (_) {
      return PaymentMethod.COD;
    }
  }
}

enum OrderStatus {
  DRAFT,
  PENDING,
  CONFIRMED,
  CONFIRMED_SYSTEM,
  CONFIRMED_SHOP,
  PROCESSING,
  DELIVERING,
  COMPLETED,
  CANCELLED,
  UNKNOWN;

  static OrderStatus fromString(String? value) {
    if (value == null) return OrderStatus.DRAFT;
    try {
      return OrderStatus.values.firstWhere((e) => e.name == value.toUpperCase(),
          orElse: () => OrderStatus.DRAFT);
    } catch (_) {
      return OrderStatus.DRAFT;
    }
  }

  // Getter hiển thị tiếng Việt (Optional - dùng cho UI)
  String get displayName {
    switch (this) {
      case OrderStatus.PENDING:
        return 'Chờ xác nhận';
      case OrderStatus.CONFIRMED:
        return 'Đã xác nhận';
      case OrderStatus.CONFIRMED_SYSTEM:
        return 'Hệ thống đã xác nhận';
      case OrderStatus.CONFIRMED_SHOP:
        return 'Chef đã xác nhận';
      case OrderStatus.PROCESSING:
        return 'Đang chuẩn bị';
      case OrderStatus.DELIVERING:
        return 'Đang giao';
      case OrderStatus.COMPLETED:
        return 'Hoàn thành';
      case OrderStatus.CANCELLED:
        return 'Đã hủy';
      default:
        return 'Nháp';
    }
  }

  bool get canEditDeliveryTime =>
      this == OrderStatus.PENDING || this == OrderStatus.CONFIRMED_SYSTEM;
}

// ================= HELPERS =================

double _parseNum(dynamic v) {
  if (v == null) return 0.0;
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v) ?? 0.0;
  return 0.0;
}

// ================= MODELS =================

class OrderLineItem {
  final String dishUid;
  final String dishName;
  final String chefName;
  final String? imageUrl;
  final int quantity;
  final double price;
  final double subTotal; // Standardized: subTotal

  const OrderLineItem({
    required this.dishUid,
    required this.dishName,
    required this.chefName,
    required this.quantity,
    required this.price,
    required this.subTotal,
    this.imageUrl,
  });

  factory OrderLineItem.fromJson(Map<String, dynamic> json) {
    // IMAGE LOGIC: UUID -> S3 URL
    String? rawImg = json['image_url']?.toString();
    String? finalUrl;
    if (rawImg != null && rawImg.isNotEmpty) {
      if (rawImg.startsWith('http')) {
        finalUrl = rawImg;
      } else {
        finalUrl = '${ApiConstants.s3BaseUrl}/$rawImg';
      }
    }

    return OrderLineItem(
      dishUid: json['dish_uid']?.toString() ?? '',
      dishName: json['dish_name']?.toString() ?? '',
      chefName: json['chef_name']?.toString() ?? '',
      imageUrl: finalUrl,
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      price: _parseNum(json['price']),
      subTotal: _parseNum(json['subtotal'] ??
          json['sub_total']), // Handle cả 2 case naming từ BE
    );
  }
}

class CustomerOrder {
  final String uid;
  final int chefId;
  final String chefName;

  // 👇 TECH LEAD ADD: Tọa độ và địa chỉ của Chef (Self Pick-up)
  final String? chefAddress;
  final double? chefLatitude;
  final double? chefLongitude;

  final double subTotal;
  final double taxAndFees;
  final double deliveryFee;

  // 👇 TECH LEAD ADD: Các trường Discount (để sau này hiện bill chi tiết)
  final double platformSubtotalDiscount;
  final double platformShippingDiscount;
  final double shopDiscount;
  final double totalDiscount;

  final double totalPrice;

  final List<OrderLineItem> items;

  final String fullName;
  final String phoneNumber;
  final String deliveryDate; // yyyy-MM-dd
  final String deliveryTime; // HH:mm:ss

  final String? deliveryAddress;
  // 👇 TECH LEAD ADD: Tọa độ khách hàng và Phương thức giao hàng
  final double? deliveryLatitude;
  final double? deliveryLongitude;
  final String deliveryType; // 'THIRD_PARTY' hoặc 'SELF_PICKUP'

  final PaymentMethod paymentMethod;
  final OrderStatus status;

  // 👇 TECH LEAD ADD: Quản lý Refund & Voucher
  final String? refundStatus;
  final String? voucherCode;

  const CustomerOrder({
    required this.uid,
    required this.chefId,
    required this.chefName,
    this.chefAddress,
    this.chefLatitude,
    this.chefLongitude,
    required this.subTotal,
    required this.taxAndFees,
    required this.deliveryFee,
    required this.platformSubtotalDiscount,
    required this.platformShippingDiscount,
    required this.shopDiscount,
    required this.totalDiscount,
    required this.totalPrice,
    required this.items,
    required this.fullName,
    required this.phoneNumber,
    required this.deliveryDate,
    required this.deliveryTime,
    this.deliveryAddress,
    this.deliveryLatitude,
    this.deliveryLongitude,
    required this.deliveryType,
    required this.paymentMethod,
    required this.status,
    this.refundStatus,
    this.voucherCode,
  });

  int get totalQuantity => items.fold(0, (sum, item) => sum + item.quantity);

  // Helper an toàn để parse số (tránh lỗi null hoặc parse String sang double)
  static double _parseNum(dynamic v) {
    if (v == null) return 0.0;
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v) ?? 0.0;
    return 0.0;
  }

  // Helper parse số có thể null (dành cho tọa độ)
  static double? _parseNullableNum(dynamic v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v);
    return null;
  }

  factory CustomerOrder.fromJson(Map<String, dynamic> json) {
    final rawItems = (json['items'] as List? ?? const []);

    return CustomerOrder(
      uid: json['uid']?.toString() ?? '',
      chefId: (json['chef_id'] as num?)?.toInt() ?? 0,
      chefName: json['chef_name']?.toString() ?? 'Unknown Chef',
      chefAddress: json['chef_address']?.toString(),
      chefLatitude: _parseNullableNum(json['chef_latitude']),
      chefLongitude: _parseNullableNum(json['chef_longitude']),
      subTotal: _parseNum(json['sub_total']),
      taxAndFees: _parseNum(json['tax_and_fees']),
      deliveryFee: _parseNum(json['delivery_fee']),
      platformSubtotalDiscount: _parseNum(json['platform_subtotal_discount']),
      platformShippingDiscount: _parseNum(json['platform_shipping_discount']),
      shopDiscount: _parseNum(json['shop_discount']),
      totalDiscount: _parseNum(json['total_discount']),
      totalPrice: _parseNum(json['total_price']),
      items: rawItems
          .map((e) => OrderLineItem.fromJson(Map<String, dynamic>.from(e)))
          .toList(),
      fullName: json['full_name']?.toString() ?? '',
      phoneNumber: json['phone_number']?.toString() ?? '',
      deliveryDate: json['delivery_date']?.toString() ?? '',
      deliveryTime: json['delivery_time']?.toString() ?? '',
      deliveryAddress: json['delivery_address']?.toString(),
      deliveryLatitude: _parseNullableNum(json['delivery_latitude']),
      deliveryLongitude: _parseNullableNum(json['delivery_longitude']),
      deliveryType: json['delivery_type']?.toString() ?? 'THIRD_PARTY',
      paymentMethod:
          PaymentMethod.fromString(json['payment_method']?.toString()),
      status: OrderStatus.fromString(json['status']?.toString()),
      refundStatus: json['refund_status']?.toString(),
      voucherCode: json['voucher_code']?.toString(),
    );
  }

  CustomerOrder copyWith({
    String? uid,
    int? chefId,
    String? chefName,
    String? chefAddress,
    double? chefLatitude,
    double? chefLongitude,
    double? subTotal,
    double? taxAndFees,
    double? deliveryFee,
    double? platformSubtotalDiscount,
    double? platformShippingDiscount,
    double? shopDiscount,
    double? totalDiscount,
    double? totalPrice,
    List<OrderLineItem>? items,
    String? fullName,
    String? phoneNumber,
    String? deliveryDate,
    String? deliveryTime,
    String? deliveryAddress,
    double? deliveryLatitude,
    double? deliveryLongitude,
    String? deliveryType,
    PaymentMethod? paymentMethod,
    OrderStatus? status,
    String? refundStatus,
    String? voucherCode,
  }) {
    return CustomerOrder(
      uid: uid ?? this.uid,
      chefId: chefId ?? this.chefId,
      chefName: chefName ?? this.chefName,
      chefAddress: chefAddress ?? this.chefAddress,
      chefLatitude: chefLatitude ?? this.chefLatitude,
      chefLongitude: chefLongitude ?? this.chefLongitude,
      subTotal: subTotal ?? this.subTotal,
      taxAndFees: taxAndFees ?? this.taxAndFees,
      deliveryFee: deliveryFee ?? this.deliveryFee,
      platformSubtotalDiscount:
          platformSubtotalDiscount ?? this.platformSubtotalDiscount,
      platformShippingDiscount:
          platformShippingDiscount ?? this.platformShippingDiscount,
      shopDiscount: shopDiscount ?? this.shopDiscount,
      totalDiscount: totalDiscount ?? this.totalDiscount,
      totalPrice: totalPrice ?? this.totalPrice,
      items: items ?? this.items,
      fullName: fullName ?? this.fullName,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      deliveryDate: deliveryDate ?? this.deliveryDate,
      deliveryTime: deliveryTime ?? this.deliveryTime,
      deliveryAddress: deliveryAddress ?? this.deliveryAddress,
      deliveryLatitude: deliveryLatitude ?? this.deliveryLatitude,
      deliveryLongitude: deliveryLongitude ?? this.deliveryLongitude,
      deliveryType: deliveryType ?? this.deliveryType,
      paymentMethod: paymentMethod ?? this.paymentMethod,
      status: status ?? this.status,
      refundStatus: refundStatus ?? this.refundStatus,
      voucherCode: voucherCode ?? this.voucherCode,
    );
  }
}
