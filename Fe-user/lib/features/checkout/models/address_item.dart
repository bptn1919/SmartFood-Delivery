class AddressItem {
  final int id;
  final String fullAddress;
  final bool selected;

  const AddressItem({
    required this.id,
    required this.fullAddress,
    required this.selected,
  });

  factory AddressItem.fromJson(Map<String, dynamic> j) => AddressItem(
        id: (j['id'] ?? 0) is int ? j['id'] : int.tryParse('${j['id']}') ?? 0,
        fullAddress: (j['full_address'] ?? '').toString(),
        selected: (j['selected'] ?? false) == true,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'full_address': fullAddress,
        'selected': selected,
      };

  AddressItem copyWith({
    int? id,
    String? fullAddress,
    bool? selected,
  }) =>
      AddressItem(
        id: id ?? this.id,
        fullAddress: fullAddress ?? this.fullAddress,
        selected: selected ?? this.selected,
      );
}
