// import 'package:flutter_application_1/models/customer_order.dart' show OrderLineItem;

// double _numOrStringToDouble(dynamic v) {
//   if (v == null) return 0;
//   if (v is num) return v.toDouble();
//   if (v is String) return double.tryParse(v) ?? 0;
//   return 0;
// }

// class OrderDetail {
//   final String uid;

//   // Thông tin chef
//   final int chefId;
//   final String chefName;

//   // Tiền
//   final double subTotal;
//   final double taxAndFees;
//   final double deliveryFee;
//   final double totalPrice;

//   // Items trong order
//   final List<OrderLineItem> items;

//   // Thông tin khách + giao hàng
//   final String fullName;
//   final String phoneNumber;
//   final String deliveryDate; // yyyy-MM-dd
//   final String deliveryTime; // HH:mm:ss / Z
//   final String? deliveryAddress;

//   // Thanh toán & trạng thái
//   final String paymentMethod; // "COD" | "MOMO"
//   final String status;        // "DRAFT" | "PENDING" | ...

//   const OrderDetail({
//     required this.uid,
//     required this.chefId,
//     required this.chefName,
//     required this.subTotal,
//     required this.taxAndFees,
//     required this.deliveryFee,
//     required this.totalPrice,
//     required this.items,
//     required this.fullName,
//     required this.phoneNumber,
//     required this.deliveryDate,
//     required this.deliveryTime,
//     required this.deliveryAddress,
//     required this.paymentMethod,
//     required this.status,
//   });

//   /// Tổng số lượng món trong order
//   int get totalQuantity =>
//       items.fold(0, (sum, item) => sum + item.quantity);

//   factory OrderDetail.fromJson(Map<String, dynamic> json) {
//     final rawItems = (json['items'] as List? ?? const []);

//     return OrderDetail(
//       uid: json['uid']?.toString() ?? '',

//       chefId: (json['chef_id'] as num?)?.toInt() ?? 0,
//       chefName: json['chef_name']?.toString() ?? 'Unknown Chef',

//       subTotal: _numOrStringToDouble(json['sub_total']),
//       taxAndFees: _numOrStringToDouble(json['tax_and_fees']),
//       deliveryFee: _numOrStringToDouble(json['delivery_fee']),
//       totalPrice: _numOrStringToDouble(json['total_price']),

//       items: rawItems
//           .map((e) => OrderLineItem.fromJson(Map<String, dynamic>.from(e)))
//           .toList(),

//       fullName: json['full_name']?.toString() ?? '',
//       phoneNumber: json['phone_number']?.toString() ?? '',
//       deliveryDate: json['delivery_date']?.toString() ?? '',
//       deliveryTime: json['delivery_time']?.toString() ?? '',
//       deliveryAddress: json['delivery_address']?.toString(),

//       paymentMethod: json['payment_method']?.toString() ?? 'COD',
//       status: json['status']?.toString() ?? 'DRAFT',
//     );
//   }
// }
