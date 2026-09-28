class CustomerAddress {
  final int id;
  final String fullAddress;
  final bool selected;

  const CustomerAddress({
    required this.id,
    required this.fullAddress,
    required this.selected,
  });

  /// Hỗ trợ State Management (Cập nhật UI mà không mutate object gốc)
  CustomerAddress copyWith({
    int? id,
    String? fullAddress,
    bool? selected,
  }) {
    return CustomerAddress(
      id: id ?? this.id,
      fullAddress: fullAddress ?? this.fullAddress,
      selected: selected ?? this.selected,
    );
  }

  factory CustomerAddress.fromJson(Map<String, dynamic> json) {
    // Robust parsing cho ID (chấp nhận cả int và String số)
    final idVal = json['id'];
    final int parsedId = (idVal is int) 
        ? idVal 
        : int.tryParse(idVal?.toString() ?? '0') ?? 0;

    // Robust parsing cho Boolean (chấp nhận true, 1, "true")
    final selVal = json['selected'];
    final bool isSelected = (selVal == true || selVal == 1 || selVal.toString().toLowerCase() == 'true');

    return CustomerAddress(
      id: parsedId,
      fullAddress: (json['full_address'] ?? '').toString(),
      selected: isSelected,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'full_address': fullAddress,
    'selected': selected,
  };

  @override
  String toString() => 'CustomerAddress(id: $id, selected: $selected, address: $fullAddress)';
  
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CustomerAddress &&
          runtimeType == other.runtimeType &&
          id == other.id &&
          selected == other.selected &&
          fullAddress == other.fullAddress;

  @override
  int get hashCode => id.hashCode ^ selected.hashCode ^ fullAddress.hashCode;
}