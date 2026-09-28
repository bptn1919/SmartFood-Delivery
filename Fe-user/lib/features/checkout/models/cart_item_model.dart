class CartItemModel {
  final String uid;
  final String dishUid;
  final String dishName;
  final String chefName;
  final double price;
  final int quantity;
  final DateTime deliveryDate;
  final bool isSelected;
  final String? imageUrl; // Added field

  const CartItemModel({
    required this.uid,
    required this.dishUid,
    required this.dishName,
    required this.chefName,
    required this.price,
    required this.quantity,
    required this.deliveryDate,
    required this.isSelected,
    this.imageUrl,
  });

  factory CartItemModel.fromJson(Map<String, dynamic> json) {
    final sel = json['is_selected'];
    final boolSelected = (sel == true || sel == 1 || sel == '1');
    
    // Normalize Chef Name
    final chef = (json['chef_name'] ?? json['chefName'] ?? '').toString();

    // IMAGE LOGIC: UUID -> S3 URL
    String? rawImg = json['image'] ?? json['image_url']; // Check key từ BE
    String? finalUrl;
    if (rawImg != null && rawImg.isNotEmpty) {
      if (rawImg.startsWith('http')) {
        finalUrl = rawImg;
      } else {
        // Giả sử ApiConstants.s3BaseUrl là biến chứa domain S3
        finalUrl = 'https://amomeal-bucket.s3.ap-southeast-1.amazonaws.com/$rawImg'; 
      }
    }

    return CartItemModel(
      uid: json['uid']?.toString() ?? '',
      dishUid: json['dish_uid']?.toString() ?? '',
      dishName: json['dish_name']?.toString() ?? '',
      chefName: chef,
      price: (json['price'] is num) ? (json['price'] as num).toDouble() : 0.0,
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      deliveryDate: DateTime.tryParse(json['delivery_date']?.toString() ?? '') ?? DateTime.now(),
      isSelected: boolSelected,
      imageUrl: finalUrl,
    );
  }

  CartItemModel copyWith({
    String? uid,
    String? dishUid,
    String? dishName,
    String? chefName,             // <— NEW
    double? price,
    int? quantity,
    DateTime? deliveryDate,
    bool? isSelected,
  }) {
    return CartItemModel(
      uid: uid ?? this.uid,
      dishUid: dishUid ?? this.dishUid,
      dishName: dishName ?? this.dishName,
      chefName: chefName ?? this.chefName,      // <— NEW
      price: price ?? this.price,
      quantity: quantity ?? this.quantity,
      deliveryDate: deliveryDate ?? this.deliveryDate,
      isSelected: isSelected ?? this.isSelected,
    );
  }

  // toJson ... (giữ nguyên cấu trúc nhưng thêm image nếu cần gửi lên)
}