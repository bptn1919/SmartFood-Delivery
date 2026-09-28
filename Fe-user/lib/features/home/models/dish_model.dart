class DishModel {
  final String uid;
  final String name;
  final String? description;
  final String? location;
  final int? locationId; // Thêm locationId để map với DishLocationModel
  final double price;
  final String status;
  final String category;
  final int reviewCount;
  final int soldCount;
  final int availableQuantity;
  final String? imageUrl;
  final String? chefName;
  final double? avgRating; // Thêm avg_rating từ API

  final bool isFavorite; // Trường mới để đánh dấu món ăn yêu thích
  final bool allergyWarning;
  final List<String>? allergens;
  

  DishModel({
    required this.uid,
    required this.name,
    this.description,
    this.location,
    this.locationId,
    required this.price,
    required this.status,
    required this.category,
    required this.reviewCount,
    required this.soldCount,
    required this.availableQuantity,
    this.imageUrl,
    this.chefName,
    this.avgRating,
    
    this.isFavorite = false,
    this.allergyWarning = false,
    this.allergens,

  });

  factory DishModel.fromJson(Map<String, dynamic> json) {
    // Logic lấy ảnh đa năng
    String? img;

    // Trường hợp 1: API trả về public_url trực tiếp
    if (json['public_url'] is String && json['public_url'].toString().isNotEmpty) {
      img = json['public_url'];
    }
    // Trường hợp 2: API trả về attachment là object có public_url
    else if (json['attachment'] is Map) {
      img = json['attachment']['public_url']?.toString();
    }
    // Trường hợp 3: API trả về attachment là UUID
    else if (json['attachment'] is String) {
      String raw = json['attachment'];
      if (raw.startsWith('http')) {
        img = raw;
      } else if (raw.isNotEmpty) {
        // Nếu là UUID -> Tự nối vào Base URL S3
        img = "https://amomeal-bucket.s3.ap-southeast-1.amazonaws.com/$raw";
      }
    }
    // Trường hợp 4: API trả về file field (cho chef)
    else if (json['file'] is String) {
      img = json['file'];
    }
    // Trường hợp 5: API trả về avatar field (cho chef)
    else if (json['avatar'] is String) {
      img = json['avatar'];
    }

    return DishModel(
      uid: json['uid']?.toString() ?? json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? 'Untitled Dish',
      description: json['description']?.toString(),

      location: json['location']?.toString(),
      locationId: int.tryParse(json['location_id']?.toString() ?? "0"),

      // Parse giá an toàn
      price: double.tryParse(json['price']?.toString() ?? "0") ?? 0.0,
      
      status: json['status']?.toString() ?? 'AVAILABLE',
      category: json['category']?.toString() ?? 'FOOD',

      reviewCount: int.tryParse(json['review_count']?.toString() ?? "0") ?? 0,
      soldCount: int.tryParse(json['sold_count']?.toString() ?? "0") ?? 0,
      
      availableQuantity: int.tryParse(json['in_stock']?.toString() ?? "0") ?? 0,
      
      imageUrl: img,
      
      chefName: json['full_name_of_chef']?.toString() ?? json['fullname_of_chef']?.toString(),
      
      // Parse avg_rating từ API
      avgRating: double.tryParse(json['final_score']?.toString() ?? "0"),
      
      isFavorite: json['is_favorite'] ?? false,
      allergyWarning: json['allergy_warning'] ?? false,
      allergens: List<String>.from(json['allergen_ingredients'] ?? []),
    );
  }

  @override
  String toString() {
    return 'DishModel(uid: $uid, name: $name, price: $price, status: $status, isFavorite: $isFavorite, allergens: $allergens, allergyWarning: $allergyWarning)';
  }
}