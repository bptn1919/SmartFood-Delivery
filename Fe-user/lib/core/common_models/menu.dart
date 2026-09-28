class Menu {
  final String uid;
  final String name;
  final String? description;
  final String? status;
  final int? chef;

  Menu({required this.uid, required this.name, this.description, this.status, this.chef});

  factory Menu.fromJson(Map<String, dynamic> json) {
    return Menu(
      uid: (json['uid'] ?? '').toString(),
      name: (json['name'] ?? '').toString(),
      description: json['description']?.toString(),
      status: json['status']?.toString(),
      chef: json['chef'] is int ? json['chef'] : int.tryParse('${json['chef']}'),
    );
  }
}