class MenuDishModel {
  final String uid;
  final String name;
  final String description;
  final String category;
  final double price;
  final String status;
  final String? publicUrl;
  final bool active;
  final int position;

  MenuDishModel({
    required this.uid,
    required this.name,
    required this.description,
    required this.category,
    required this.price,
    required this.status,
    this.publicUrl,
    required this.active,
    required this.position,
  });

  factory MenuDishModel.fromJson(Map<String, dynamic> json) {
    return MenuDishModel(
      uid: json['uid'] ?? '',
      name: json['name'] ?? '',
      description: json['description'] ?? '',
      category: json['category'] ?? '',
      // Xử lý an toàn cho kiểu số
      price: (json['price'] != null) ? double.parse(json['price'].toString()) : 0.0,
      status: json['status'] ?? 'UNAVAILABLE',
      publicUrl: json['public_url'],
      active: json['active'] ?? false,
      position: json['position'] ?? 0,
    );
  }
}