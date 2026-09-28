class CustomerAddress {
  final int id;
  final String fullAddress;
  final bool selected;

  const CustomerAddress({
    required this.id,
    required this.fullAddress,
    required this.selected,
  });

  factory CustomerAddress.fromJson(Map<String, dynamic> json) {
    return CustomerAddress(
      id: (json['id'] ?? 0) is int ? (json['id'] ?? 0) : int.tryParse('${json['id']}') ?? 0,
      fullAddress: (json['full_address'] ?? '').toString(),
      selected: (json['selected'] ?? false) as bool,
    );
  }
}
