class AddressModel {
  final int id;
  final String fullAddress;
  final bool selected;

  AddressModel({
    required this.id,
    required this.fullAddress,
    required this.selected,
  });

  factory AddressModel.fromJson(Map<String, dynamic> json) {
    return AddressModel(
      id: json['id'] as int,
      fullAddress: json['full_address'] as String,
      selected: json['selected'] as bool,
    );
  }

  AddressModel copyWith({
    int? id,
    String? fullAddress,
    bool? selected,
  }) {
    return AddressModel(
      id: id ?? this.id,
      fullAddress: fullAddress ?? this.fullAddress,
      selected: selected ?? this.selected,
    );
  }
}