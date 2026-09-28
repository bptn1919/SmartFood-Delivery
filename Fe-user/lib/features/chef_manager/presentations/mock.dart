class MockVoucher {
  final String code;
  final String startDate;
  final String endDate;
  final String description;
  final int discount;
  final bool isActive;

  MockVoucher({
    required this.code,
    required this.startDate,
    required this.endDate,
    required this.description,
    required this.discount,
    required this.isActive,
  });
}

// Data giả lập theo Figma
final List<MockVoucher> mockVouchers = [
  MockVoucher(code: "ASSJSNDNC", startDate: "01.01.2026", endDate: "01.02.2026", description: "\$2 discount on your first order", discount: 2, isActive: true),
  MockVoucher(code: "BSKSLALASK", startDate: "01.01.2026", endDate: "01.02.2026", description: "\$5 discount on your delivery", discount: 5, isActive: true),
  MockVoucher(code: "KNSKDNCKS", startDate: "01.01.2026", endDate: "01.02.2026", description: "Get a \$2 discount on orders placed at midnight.", discount: 2, isActive: false),
  MockVoucher(code: "ONLKNKSS", startDate: "01.01.2026", endDate: "01.02.2026", description: "Get a \$2 discount on new items on the menu.", discount: 2, isActive: false),
];