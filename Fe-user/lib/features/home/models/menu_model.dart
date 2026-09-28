class MenuModel {
  final String uid;
  final String name;
  final String? description;
  final String status;
  // Có thể thêm List<DishModel> nếu cần

  MenuModel({
    required this.uid,
    required this.name,
    this.description,
    required this.status,
  });

  factory MenuModel.fromJson(Map<String, dynamic> json) {
    return MenuModel(
      uid: json['uid'] ?? '',
      name: json['name'] ?? '',
      description: json['description'],
      status: json['status'] ?? 'ACTIVE',
    );
  }
}