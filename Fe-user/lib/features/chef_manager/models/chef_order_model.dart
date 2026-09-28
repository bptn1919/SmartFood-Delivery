class ChefOrderModel {
  final String uid;
  final String displayId; // ID rút gọn để hiển thị (VD: #2509)
  final int customerUserId;
  final String createdDate;
  final double totalPrice;
  final int totalQuantity; // Tổng số lượng các món trong đơn
  final String status;
  final String firstImage; // Ảnh của món đầu tiên để hiển thị

  ChefOrderModel({
    required this.uid,
    required this.displayId,
    required this.customerUserId,
    required this.createdDate,
    required this.totalPrice,
    required this.totalQuantity,
    required this.status,
    required this.firstImage,
  });

  static int _parseInt(dynamic value) {
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value) ?? 0;
    return 0;
  }

  static int _parseCustomerUserId(Map<String, dynamic> json) {
    final direct =
        json['customer_user_id'] ?? json['customer_id'] ?? json['user_id'];
    final directId = _parseInt(direct);
    if (directId > 0) return directId;

    final customer = json['customer'];
    if (customer is Map<String, dynamic>) {
      final nested = customer['user_id'] ?? customer['id'];
      final nestedId = _parseInt(nested);
      if (nestedId > 0) return nestedId;
    }

    final customerProfile = json['customer_profile'];
    if (customerProfile is Map<String, dynamic>) {
      final nested = customerProfile['user_id'] ?? customerProfile['id'];
      final nestedId = _parseInt(nested);
      if (nestedId > 0) return nestedId;
    }

    return 0;
  }

  factory ChefOrderModel.fromJson(Map<String, dynamic> json) {
    // 1. Xử lý Items để lấy tổng số lượng và ảnh đầu tiên
    List<dynamic> items = json['items'] ?? [];

    int qty = 0;
    String img = "";

    if (items.isNotEmpty) {
      // Cộng dồn quantity
      qty = items.fold(0, (sum, item) => sum + (item['quantity'] as int? ?? 1));

      // Lấy ảnh món đầu tiên (nếu có)
      // Lưu ý: Cấu trúc item tùy thuộc vào BE, đây là giả định chuẩn
      if (items[0]['dish'] != null) {
        img = items[0]['dish']['image'] ?? items[0]['dish']['public_url'] ?? "";
      }
    }

    // 2. Format Date (Giả sử BE trả về ISO String)
    String dateStr = json['created_at'] ?? DateTime.now().toIso8601String();
    // Bạn có thể dùng thư viện intl để format đẹp hơn sau này

    return ChefOrderModel(
      uid: json['uid'] ?? "",
      displayId: "#${(json['id'] ?? '000').toString()}", // Hoặc cắt chuỗi UID
      customerUserId: _parseCustomerUserId(json),
      createdDate: dateStr, // Tạm thời giữ raw string, UI sẽ format
      totalPrice: double.tryParse(json['total_price'].toString()) ?? 0.0,
      totalQuantity: qty,
      status: json['status'] ?? "PENDING",
      firstImage: img,
    );
  }
}
