enum PaymentMethodEnum {
  COD,
  PAYOS,
  // Thêm các phương thức khác nếu cần
}

/// Extension để lấy giá trị String (ví dụ: "COD") từ enum
extension PaymentMethodExtension on PaymentMethodEnum {
  String get apiValue {
    // Trả về tên của enum, ví dụ: PaymentMethodEnum.COD -> "COD"
    return this.toString().split('.').last;
  }
}