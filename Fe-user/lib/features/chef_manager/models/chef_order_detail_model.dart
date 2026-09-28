class ChefOrderDetailModel {
  final String uid;
  final String displayId; // VD: #2509
  final int customerUserId;
  final String customerName;
  final String phoneNumber;
  final String address;
  final String createdDate;

  // Payment info
  final double subTotal;
  final double taxAndFees;
  final double deliveryFee;
  final double totalPrice;
  final String paymentMethod;
  final String status;
  final String? note; // Ghi chú đơn hàng

  // Danh sách món ăn
  final List<ChefOrderItem> items;

  ChefOrderDetailModel({
    required this.uid,
    required this.displayId,
    required this.customerUserId,
    required this.customerName,
    required this.phoneNumber,
    required this.address,
    required this.createdDate,
    required this.subTotal,
    required this.taxAndFees,
    required this.deliveryFee,
    required this.totalPrice,
    required this.paymentMethod,
    required this.status,
    this.note,
    required this.items,
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

  factory ChefOrderDetailModel.fromJson(Map<String, dynamic> json) {
    return ChefOrderDetailModel(
      uid: json['uid'] ?? "",
      displayId: "#${json['id'] ?? '000'}", // Map ID ngắn nếu có
      customerUserId: _parseCustomerUserId(json),
      customerName: json['full_name'] ?? "Unknown Customer",
      phoneNumber: json['phone_number'] ?? "",
      address: json['delivery_address'] ?? "",
      createdDate: json['delivery_date'] ?? "", // Hoặc created_at tùy API

      // Parse tiền tệ an toàn
      subTotal: double.tryParse(json['sub_total'].toString()) ?? 0.0,
      taxAndFees: double.tryParse(json['tax_and_fees'].toString()) ?? 0.0,
      deliveryFee: double.tryParse(json['delivery_fee'].toString()) ?? 0.0,
      totalPrice: double.tryParse(json['total_price'].toString()) ?? 0.0,

      paymentMethod: json['payment_method'] ?? "COD",
      status: json['status'] ?? "PENDING",
      note: json['note'], // Nếu API có trả về note

      items: (json['items'] as List<dynamic>?)
              ?.map((e) => ChefOrderItem.fromJson(e))
              .toList() ??
          [],
    );
  }
}

class ChefOrderItem {
  final String name;
  final String image;
  final double price;
  final int quantity;
  final String date; // Ngày hiển thị dưới tên món (như trong UI)

  ChefOrderItem({
    required this.name,
    required this.image,
    required this.price,
    required this.quantity,
    required this.date,
  });

  factory ChefOrderItem.fromJson(Map<String, dynamic> json) {
    return ChefOrderItem(
      name: json['dish_name'] ?? json['full_name'] ?? "Món ăn",
      // Xử lý ảnh (ưu tiên public_url nếu object dish lồng nhau)
      image: json['image_url'] ?? "",
      price: double.tryParse(json['price'].toString()) ?? 0.0,
      quantity: json['quantity'] ?? 1,
      date: json['created_at'] ?? "Oct 24, 2025", // Giả lập date
    );
  }
}
