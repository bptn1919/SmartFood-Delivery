import 'attachment.dart';

class DishResponse {
  final String name;
  final String? uid;                 
  final String? category;
  final String? description;
  final double? price;
  final double? availableQuantity;
  final String? status;
  final AttachmentResponse? attachment;

  DishResponse({
    required this.name,
    this.uid,                        
    this.category,
    this.description,
    this.price,
    this.availableQuantity,
    this.status,
    this.attachment,
  });

  factory DishResponse.fromJson(Map<String, dynamic> json) {
    return DishResponse(
      name: json['name'] ?? '',
      uid: json['uid']?.toString(), 
      category: json['category'] as String?,
      description: json['description'] as String?,
      price: json['price'] != null
          ? double.tryParse(json['price'].toString())
          : null,
      availableQuantity: (json['available_quantity'] is num)
          ? (json['available_quantity'] as num).toDouble()
          : (json['available_quantity'] != null
              ? double.tryParse(json['available_quantity'].toString())
              : null),
      status: json['status'] as String?,
      attachment: json['attachment'] != null
          ? AttachmentResponse.fromJson(
              json['attachment'] is Map<String, dynamic>
                  ? (json['attachment'] as Map<String, dynamic>)
                  : {'public_url': json['attachment'].toString()},
            )
          : null,
    );
  }

  Map<String, dynamic> toJson() => {
        'name': name,
        'uid': uid,                                  
        'category': category,
        'description': description,
        'price': price,
        'available_quantity': availableQuantity,
        'status': status,
        'attachment': attachment?.toJson(),
      };
}
