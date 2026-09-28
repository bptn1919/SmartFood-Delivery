import 'package:intl/intl.dart';

class VoucherModel {
  final String uid;
  final String code;
  final String name;
  final String description;
  final String discountType;
  final double discountValue;
  final double minOrderAmount;
  final DateTime startDate;
  final DateTime endDate;
  final bool isActive;

  VoucherModel({
    required this.uid,
    required this.code,
    required this.name,
    required this.description,
    required this.discountType,
    required this.discountValue,
    required this.minOrderAmount,
    required this.startDate,
    required this.endDate,
    required this.isActive,
  });

  factory VoucherModel.fromJson(Map<String, dynamic> json) {
    return VoucherModel(
      uid: json['uid'] ?? "",
      code: json['code'] ?? "",
      name: json['name'] ?? "",
      description: json['description'] ?? "",
      discountType: json['discount_type'] ?? "FIXED_AMOUNT",
      // Parse an toàn cho số liệu
      discountValue: double.tryParse(json['discount_value'].toString()) ?? 0,
      minOrderAmount: double.tryParse(json['min_order_amount'].toString()) ?? 0,
      // Parse ngày giờ chuẩn ISO 8601 từ backend
      startDate: json['start_date'] != null ? DateTime.parse(json['start_date']).toLocal() : DateTime.now(),
      endDate: json['end_date'] != null ? DateTime.parse(json['end_date']).toLocal() : DateTime.now(),
      isActive: json['is_active'] ?? false,
    );
  }

  // --- Helpers cho UI ---
  // Format ngày theo chuẩn UI: DD.MM.YYYY
  String get formattedStartDate => DateFormat('dd.MM.yyyy').format(startDate);
  String get formattedEndDate => DateFormat('dd.MM.yyyy').format(endDate);

  // Format mức giảm giá: Nếu % thì hiện "15%", nếu Fixed thì hiện "$15" hoặc "15.000đ"
  String get displayDiscount {
    if (discountType == "PERCENTAGE") {
      return "${discountValue.toStringAsFixed(0)}%";
    } else {
      // Tuỳ thuộc bạn đang dùng $ hay VNĐ trong App
      return "\$${discountValue.toStringAsFixed(0)}"; 
    }
  }
}