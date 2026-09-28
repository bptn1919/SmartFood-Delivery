class CustomerProfileModel {
  final String? avatar;
  final String? fullname;
  final String? mail;
  final String? phone;
  final String? bio;
  final int? points;
  final List<AddressModel>? addresses;

  final String? dietMode;
  final String? dietLevel;
  final String? allergyMode;

  CustomerProfileModel({
    this.avatar,
    this.fullname,
    this.mail,
    this.phone,
    this.bio,
    this.points,
    this.addresses,
    this.dietMode,
    this.dietLevel,
    this.allergyMode,
  });

  factory CustomerProfileModel.fromJson(Map<String, dynamic> json) {
    return CustomerProfileModel(
      avatar: json['avatar'],
      fullname: json['fullname'],
      mail: json['mail'],
      phone: json['phone'],
      bio: json['bio'],
      points: json['points'],
      addresses: json['addresses'] != null
          ? (json['addresses'] as List).map((i) => AddressModel.fromJson(i)).toList()
          : null,

      dietMode: json['diet_mode'],
      dietLevel: json['diet_level'],
      allergyMode: json['allergy_mode'],
    );
  }
}

class AddressModel {
  final int id;
  final String? fullAddress;
  final String? address;
  final String? street;
  final String? ward;
  final String? district;
  final String? city;
  final bool? selected;
  
  final double? latitude;
  final double? longitude;

  AddressModel({
    required this.id,
    this.fullAddress,
    this.address,
    this.street,
    this.ward,
    this.district,
    this.city,
    this.selected,
    this.latitude,
    this.longitude,
  });

  factory AddressModel.fromJson(Map<String, dynamic> json) {
    return AddressModel(
      id: json['id'] ?? 0,
      fullAddress: json['full_address'],
      address: json['address'],
      street: json['street'],
      ward: json['ward'],
      district: json['district'],
      city: json['city'],
      selected: json['selected'],
      
      latitude: json['latitude'] != null ? (json['latitude'] as num).toDouble() : null,
      longitude: json['longitude'] != null ? (json['longitude'] as num).toDouble() : null,
    );
  }
  
  // (Tùy chọn) Thêm hàm toJson để dễ dàng đẩy data lên lại BE
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'address': address,
      'street': street,
      'ward': ward,
      'district': district,
      'city': city,
      'latitude': latitude,
      'longitude': longitude,
      'selected': selected,
    };
  }
}