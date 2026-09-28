import 'package:testing/features/home/models/dish_model.dart';

class BetterAlternativeDishModel {
  final String dishUid;
  final String dishName;
  final String publicUrl;
  final double price;
  final double avgRating;
  final int totalReviews;
  final double issueRate;
  final double score;
  final String explain;

  BetterAlternativeDishModel({
    required this.dishUid,
    required this.dishName,
    required this.publicUrl,
    required this.price,
    required this.avgRating,
    required this.totalReviews,
    required this.issueRate,
    required this.score,
    required this.explain,
  });

  factory BetterAlternativeDishModel.fromJson(Map<String, dynamic> json) {
    // 💡 Hàm tiện ích giúp parse số liệu an toàn, chống crash do khác kiểu int/double từ JSON
    double parseDouble(dynamic value) {
      if (value == null) return 0.0;
      if (value is num) return value.toDouble();
      if (value is String) return double.tryParse(value) ?? 0.0;
      return 0.0;
    }

    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is num) return value.toInt();
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    return BetterAlternativeDishModel(
      dishUid: json['dish_uid']?.toString() ?? '',
      dishName: json['dish_name']?.toString() ?? 'Untitled Dish', // Đã fix tận gốc lỗi Untitled
      publicUrl: json['public_url']?.toString() ?? '',
      price: parseDouble(json['price']),
      avgRating: parseDouble(json['avg_rating']),
      totalReviews: parseInt(json['total_reviews']),
      issueRate: parseDouble(json['issue_rate']),
      score: parseDouble(json['score']),
      explain: json['explain']?.toString() ?? '',
    );
  }

  DishModel toDishModel() {
    return DishModel(
      uid: dishUid,
      name: dishName,
      imageUrl: publicUrl, 
      price: price.toDouble(), 
      avgRating: avgRating, 
      
      status: "AVAILABLE", 
      category: "Alternative",
      reviewCount: totalReviews,
      soldCount: 0, 
      description: explain.isNotEmpty ? explain : "Suggested alternative", 
      availableQuantity: 22, 
    );
  }
}