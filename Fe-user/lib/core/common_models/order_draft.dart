import 'order_line.dart';

class OrderDraft {
  final String uid;
  final String fullName;
  final String phoneNumber;
  final String deliveryDate;   // yyyy-MM-dd
  final String deliveryTime;   // "HH:mm[:ss][.SSS][Z]"
  final String deliveryAddress;
  final String paymentMethod;
  final String status;         // có thể rỗng ở checkout

  final double subtotal;       // sub_total
  final double taxAndFees;     // tax_and_fees
  final double deliveryFee;    // delivery_fee
  final double totalAmount;    // total_price

  final List<OrderDraftLine> lines;

  const OrderDraft({
    required this.uid,
    required this.fullName,
    required this.phoneNumber,
    required this.deliveryDate,
    required this.deliveryTime,
    required this.deliveryAddress,
    required this.paymentMethod,
    required this.status,
    required this.subtotal,
    required this.taxAndFees,
    required this.deliveryFee,
    required this.totalAmount,
    required this.lines,
  });

  // ---- helpers ----
  static double _toDouble(dynamic v) {
    if (v == null) return 0;
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v) ?? 0;
    return 0;
  }

  /// Có thể truyền trực tiếp `data` hoặc nguyên response:
  /// { data: {...}, ... } | { ... }
  factory OrderDraft.fromJson(Map<String, dynamic> json) {
    final src = (json['data'] is Map)
        ? Map<String, dynamic>.from(json['data'])
        : Map<String, dynamic>.from(json);

    // ---- FLATTEN items ----
    // Case A (mới): có 'orders' -> gộp tất cả orders[].items[] và gắn chef_id/chef_name vào line
    final List<OrderDraftLine> flatLines = <OrderDraftLine>[];
    final ordersRaw = src['orders'];
    if (ordersRaw is List) {
      for (final o in ordersRaw) {
        final ord = Map<String, dynamic>.from(o as Map);
        final chefId = (ord['chef_id'] as num?)?.toInt();
        final chefName = (ord['chef_name'] ?? '').toString();

        final its = ord['items'];
        if (its is List) {
          for (final it in its) {
            final m = Map<String, dynamic>.from(it as Map);

            // Chuẩn hoá key cho OrderDraftLine:
            // API có thể dùng 'subtotal' thay vì 'line_total'
            if (!m.containsKey('line_total') && m.containsKey('subtotal')) {
              m['line_total'] = m['subtotal'];
            }
            // gắn chef info
            m['chef_id'] = chefId;
            m['chef_name'] = chefName;

            flatLines.add(OrderDraftLine.fromJson(m));
          }
        }
      }
    }

    // Case B (cũ): root có 'items'
    if (flatLines.isEmpty) {
      final itemsRaw = (src['items'] is List) ? (src['items'] as List) : const [];
      flatLines.addAll(itemsRaw
          .map((e) => OrderDraftLine.fromJson(Map<String, dynamic>.from(e)))
          .toList());
    }

    return OrderDraft(
      uid: (src['uid'] ?? '').toString(),
      fullName: (src['full_name'] ?? '').toString(),
      phoneNumber: (src['phone_number'] ?? '').toString(),
      deliveryDate: (src['delivery_date'] ?? '').toString(),
      deliveryTime: (src['delivery_time'] ?? '').toString(),
      deliveryAddress: (src['delivery_address'] ?? '').toString(),
      paymentMethod: (src['payment_method'] ?? '').toString(),
      status: (src['status'] ?? '').toString(), // checkout có thể không trả -> rỗng
      subtotal: _toDouble(src['sub_total']),
      taxAndFees: _toDouble(src['tax_and_fees']),
      deliveryFee: _toDouble(src['delivery_fee']),
      totalAmount: _toDouble(src['total_price']),
      lines: flatLines,
    );
  }

  /// toJson dùng snake_case theo API (chủ yếu cho FE tạm lưu)
  Map<String, dynamic> toJson() => {
        'uid': uid,
        'full_name': fullName,
        'phone_number': phoneNumber,
        'delivery_date': deliveryDate,
        'delivery_time': deliveryTime,
        'delivery_address': deliveryAddress,
        'payment_method': paymentMethod,
        'status': status,
        'sub_total': subtotal,
        'tax_and_fees': taxAndFees,
        'delivery_fee': deliveryFee,
        'total_price': totalAmount,
        'items': lines.map((e) => e.toJson()).toList(),
      };

  OrderDraft copyWith({
    String? uid,
    String? fullName,
    String? phoneNumber,
    String? deliveryDate,
    String? deliveryTime,
    String? deliveryAddress,
    String? paymentMethod,
    String? status,
    double? subtotal,
    double? taxAndFees,
    double? deliveryFee,
    double? totalAmount,
    List<OrderDraftLine>? lines,
  }) {
    return OrderDraft(
      uid: uid ?? this.uid,
      fullName: fullName ?? this.fullName,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      deliveryDate: deliveryDate ?? this.deliveryDate,
      deliveryTime: deliveryTime ?? this.deliveryTime,
      deliveryAddress: deliveryAddress ?? this.deliveryAddress,
      paymentMethod: paymentMethod ?? this.paymentMethod,
      status: status ?? this.status,
      subtotal: subtotal ?? this.subtotal,
      taxAndFees: taxAndFees ?? this.taxAndFees,
      deliveryFee: deliveryFee ?? this.deliveryFee,
      totalAmount: totalAmount ?? this.totalAmount,
      lines: lines ?? this.lines,
    );
  }

  @override
  String toString() =>
      'OrderDraft(uid: $uid, time: $deliveryDate $deliveryTime, total: $totalAmount)';

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is OrderDraft &&
        other.uid == uid &&
        other.fullName == fullName &&
        other.phoneNumber == phoneNumber &&
        other.deliveryDate == deliveryDate &&
        other.deliveryTime == deliveryTime &&
        other.deliveryAddress == deliveryAddress &&
        other.paymentMethod == paymentMethod &&
        other.status == status &&
        other.subtotal == subtotal &&
        other.taxAndFees == taxAndFees &&
        other.deliveryFee == deliveryFee &&
        other.totalAmount == totalAmount &&
        _listEquals(other.lines, lines);
  }

  @override
  int get hashCode =>
      uid.hashCode ^
      fullName.hashCode ^
      phoneNumber.hashCode ^
      deliveryDate.hashCode ^
      deliveryTime.hashCode ^
      deliveryAddress.hashCode ^
      paymentMethod.hashCode ^
      status.hashCode ^
      subtotal.hashCode ^
      taxAndFees.hashCode ^
      deliveryFee.hashCode ^
      totalAmount.hashCode ^
      lines.hashCode;

  static bool _listEquals(List<OrderDraftLine> a, List<OrderDraftLine> b) {
    if (identical(a, b)) return true;
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
