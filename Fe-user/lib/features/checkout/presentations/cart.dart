import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import '../../home/repositories/cart_repository.dart';
import '../repositories/customer_repository.dart';
import '../../home/repositories/dish_repository.dart';

import '../../home/models/cart_model.dart';
import '../models/customer_address.dart';
import 'checkout.dart';
import '../../common/app_components.dart';

// Thêm dòng này vào khu vực import
import '../../../core/utils/image_helper.dart';

class CartPage extends StatefulWidget {
  const CartPage({super.key});

  @override
  State<CartPage> createState() => _CartPageState();
}

class _CartPageState extends State<CartPage> {
  static const String _defaultDeliveryTime = '09:00:00';

  final _cartRepo = CartRepository();
  final _customerRepo = CustomerRepository();
  final _dishRepo = DishRepository();

  bool _loading = true;
  bool _posting = false;
  String? _error;

  List<CartItemModel> _items = [];
  Map<String, Map<String, List<CartItemModel>>> grouped = {};
  double _total = 0;
  final Map<String, int> _maxQty = {};
  Map<String, bool> _dateChecked = {};

  // --- STYLING COLORS CHUẨN FIGMA ---
  final Color _kPeachBg = const Color(0xFFFFC6A6);
  final Color _kPrimaryPink = const Color(0xFFD85C6B);
  final Color _textBrown = const Color(0xFF4A2C2A);
  final Color _kLightPinkLine = const Color(0xFFF3D5D8);

  @override
  void initState() {
    super.initState();
    _load();
  }

  // Helper chuyển đổi Date sang định dạng "Oct 24, 2025"
  String _formatDisplayDate(String yyyyMmDd) {
    try {
      final d = DateTime.parse(yyyyMmDd);
      final months = [
        'Jan',
        'Feb',
        'Mar',
        'Apr',
        'May',
        'Jun',
        'Jul',
        'Aug',
        'Sep',
        'Oct',
        'Nov',
        'Dec'
      ];
      return '${months[d.month - 1]} ${d.day}, ${d.year}';
    } catch (_) {
      return yyyyMmDd;
    }
  }

  // ===========================================================================
  // UI BUILD
  // ===========================================================================
  @override
  Widget build(BuildContext context) {
    // 🔴 TODO: Bổ sung API cho Tax và Delivery ở đây

    double finalTotal = _total;

    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );

    return Scaffold(
      backgroundColor: _kPeachBg,
      body: Column(
        children: [
          // ===== HEADER CHUẨN FIGMA =====
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 60, 16, 20),
            child: Row(
              children: [
                IconButton(
                  icon: Icon(Icons.arrow_back_ios_new,
                      color: _textBrown, size: 20),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                Expanded(
                  child: Text(
                    "Your Cart",
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: _textBrown,
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(width: 48), // Placeholder cân đối
              ],
            ),
          ),

          // ===== BODY MÀU TRẮNG BO GÓC TRÒN =====
          Expanded(
            child: Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(30),
                  topRight: Radius.circular(30),
                ),
              ),
              child: _loading
                  ? Center(
                      child: CircularProgressIndicator(color: _kPrimaryPink))
                  : _error != null
                      ? Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Icon(Icons.error_outline,
                                  size: 40, color: Colors.red),
                              const SizedBox(height: 8),
                              Text(_error!,
                                  style: const TextStyle(color: Colors.red)),
                              TextButton(
                                  onPressed: _load, child: const Text("Retry")),
                            ],
                          ),
                        )
                      : _items.isEmpty
                          ? const Center(
                              child: Text('Your cart is empty.',
                                  style: TextStyle(color: Colors.grey)))
                          : RefreshIndicator(
                              onRefresh: _load,
                              color: _kPrimaryPink,
                              child: ListView(
                                padding: const EdgeInsets.all(24),
                                children: [
                                  for (final day in grouped.keys) ...[
                                    _buildDateSection(day),
                                    const SizedBox(height: 16),
                                    const Divider(
                                        height: 1,
                                        color: Color(0xFFF3D5D8),
                                        thickness: 2), // Divider hồng
                                    const SizedBox(height: 24),
                                  ],
                                ],
                              ),
                            ),
            ),
          ),
        ],
      ),

      // ===== BOTTOM SUMMARY & CHECKOUT BUTTON =====
      bottomNavigationBar: _items.isEmpty || _loading
          ? const SizedBox.shrink()
          : Container(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24)
                  .copyWith(
                bottom: MediaQuery.of(context).padding.bottom + 16,
              ),
              decoration: const BoxDecoration(
                color: Colors.white,
                boxShadow: [
                  BoxShadow(
                      color: Colors.black12,
                      blurRadius: 10,
                      offset: Offset(0, -5))
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _summaryRow("Total", currencyFormatter.format(finalTotal),
                      isTotal: true),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    height: 50,
                    child: ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: _kPrimaryPink,
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(25)),
                        elevation: 0,
                      ),
                      onPressed: (_items.where((e) => e.isSelected).isEmpty)
                          ? null
                          : _checkout,
                      child: _posting
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                  color: Colors.white, strokeWidth: 2))
                          : const Text(
                              "Checkout",
                              style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                  color: Colors.white),
                            ),
                    ),
                  ),
                ],
              ),
            ),
    );
  }

  // ===========================================================================
  // WIDGET HELPERS (GIAO DIỆN CHUẨN FIGMA)
  // ===========================================================================

  Widget _buildDateSection(String day) {
    final displayDate = _formatDisplayDate(day);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Date Header (Icon Radio đôi)
        InkWell(
          onTap: () => _toggleDate(day, !(_dateChecked[day] ?? false)),
          child: Row(
            children: [
              Icon(
                _dateChecked[day] == true
                    ? Icons.radio_button_checked
                    : Icons.radio_button_unchecked,
                color: _dateChecked[day] == true ? _kPrimaryPink : Colors.grey,
                size: 24,
              ),
              const SizedBox(width: 12),
              Text(displayDate,
                  style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: _textBrown)),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // List by Chef
        for (final chef in grouped[day]!.keys) ...[
          // Chỉ hiện tên Chef nếu thực sự cần thiết, Figma không vẽ tên Chef nên tôi thu nhỏ lại
          if (chef != 'Unknown Chef')
            Padding(
              padding: const EdgeInsets.only(left: 36, bottom: 8),
              child: Text(chef,
                  style: const TextStyle(
                      fontWeight: FontWeight.bold,
                      color: Colors.grey,
                      fontSize: 13)),
            ),
          ...grouped[day]![chef]!.map((it) => _buildCartItemRow(it, day)),
        ]
      ],
    );
  }

  Widget _buildCartItemRow(CartItemModel item, String dayKey) {
    final canSelect = _dateChecked[dayKey] == true;
    final displayDate = _formatDisplayDate(dayKey);

    final String fullImageUrl = ImageHelper.getValidUrl(item.imageUrl ?? "");

    final currencyFormatter = NumberFormat.currency(
      locale: 'vi_VN',
      symbol: 'đ',
      decimalDigits: 0,
    );

    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          // 1. Checkbox vuông bo góc
          InkWell(
            onTap: () => _toggleSelect(item, dayKey),
            child: Container(
              width: 20,
              height: 20,
              decoration: BoxDecoration(
                color: item.isSelected ? _kPrimaryPink : Colors.white,
                borderRadius: BorderRadius.circular(4),
                border: Border.all(
                    color: canSelect
                        ? (item.isSelected
                            ? _kPrimaryPink
                            : _kPrimaryPink.withOpacity(0.5))
                        : Colors.grey.withOpacity(0.3),
                    width: 1.5),
              ),
              child: item.isSelected
                  ? const Icon(Icons.check, size: 14, color: Colors.white)
                  : null,
            ),
          ),
          const SizedBox(width: 16),

          // 2. Ảnh món ăn
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: fullImageUrl.isNotEmpty
                ? Image.network(
                    fullImageUrl, // Dùng URL đã được format
                    width: 70, height: 70, cacheWidth: 140, 
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => _buildImagePlaceholder(),
                  )
                : _buildImagePlaceholder(), // Nếu không có URL thì hiện cục xám
          ),
          const SizedBox(width: 16),

          // 3. Thông tin món ăn
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Tên món và Nút xóa
                Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(
                        child: Text(item.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 16,
                                color: _textBrown)),
                      ),
                      InkWell(
                        onTap: () => _removeItem(item),
                        child: Icon(Icons.delete_outline,
                            size: 20, color: _kPrimaryPink.withOpacity(0.7)),
                      )
                    ]),

                // Ngày đặt (Icon Calendar)
                Row(
                  children: [
                    Icon(Icons.calendar_today_outlined,
                        size: 12, color: _kPrimaryPink.withOpacity(0.8)),
                    const SizedBox(width: 4),
                    Text(displayDate,
                        style: TextStyle(
                            fontSize: 11,
                            color: _kPrimaryPink.withOpacity(0.8))),
                  ],
                ),
                const SizedBox(height: 8),

                // Giá tiền và Bộ đếm (Stepper)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(currencyFormatter.format(item.price),
                        style: TextStyle(
                            color: _kPrimaryPink,
                            fontWeight: FontWeight.bold,
                            fontSize: 16)),
                    Row(
                      children: [
                        _qtyBtn(
                            isPlus: false,
                            onTap: () => item.quantity > 0
                                ? _changeQtyOptimistic(item, item.quantity - 1)
                                : null),
                        InkWell(
                          onTap: () => _showEditQuantityDialog(
                              item), // Bấm vào gọi Popup
                          borderRadius: BorderRadius.circular(8),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                                vertical: 8), // Mở rộng vùng bấm cho dễ chạm
                            child: Text('${item.quantity}',
                                style: TextStyle(
                                    fontWeight: FontWeight.bold,
                                    color: _textBrown,
                                    fontSize: 16)),
                          ),
                        ),
                        _qtyBtn(
                            isPlus: true,
                            onTap: () =>
                                _changeQtyOptimistic(item, item.quantity + 1)),
                      ],
                    )
                  ],
                )
              ],
            ),
          )
        ],
      ),
    );
  }

  Widget _summaryRow(String label, String value, {bool isTotal = false}) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label,
            style: TextStyle(
                fontWeight: isTotal ? FontWeight.bold : FontWeight.bold,
                color: _textBrown,
                fontSize: isTotal ? 16 : 14)),
        Text(value,
            style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: isTotal ? 18 : 14,
                color: _textBrown)),
      ],
    );
  }

  // Nút Stepper chuẩn Figma: Dấu (-) màu đỏ nguyên khối, Dấu (+) viền đỏ
  Widget _qtyBtn({required bool isPlus, required VoidCallback? onTap}) {
    return InkWell(
      onTap: onTap,
      child: Container(
        width: 24,
        height: 24,
        decoration: BoxDecoration(
          color: isPlus ? Colors.white : _kPrimaryPink,
          shape: BoxShape.circle,
          border: Border.all(color: _kPrimaryPink, width: 1.5),
        ),
        child: Icon(isPlus ? Icons.add : Icons.remove,
            size: 16, color: isPlus ? _kPrimaryPink : Colors.white),
      ),
    );
  }

  // ===========================================================================
  // LOGIC METHODS (Giữ nguyên toàn bộ logic cũ của bạn)
  // ===========================================================================

  String _yyyyMmDd(String dateStr) {
    if (dateStr.isEmpty) return '';

    final d = DateTime.tryParse(dateStr);
    if (d == null) return dateStr;

    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  String _capKey(String dishUid, String date) =>
      '${dishUid}_${_yyyyMmDd(date)}';

  Map<String, bool> _computeInitialDateChecks(List<CartItemModel> items) {
    final m = <String, bool>{};
    final dates = items.map((e) => _yyyyMmDd(e.deliveryDate)).toSet();
    for (final d in dates) {
      final has = items.any(
        (it) => _yyyyMmDd(it.deliveryDate) == d && it.isSelected,
      );
      m[d] = has;
    }
    return m;
  }

  void _recalcTotal() {
    _total = _items
        .where((e) => e.isSelected)
        .fold<double>(0, (s, e) => s + (e.price * e.quantity));
  }

  Future<void> _loadAvailabilitiesAndClamp() async {
    final dishUids = _items.map((e) => e.dishUid).toSet().toList();

    for (final uid in dishUids) {
      try {
        final avs = await _dishRepo.getDishAvailabilities(uid);
        for (final a in avs) {
          _maxQty[_capKey(uid, a.date.toString())] = a.quantity;
        }
      } catch (_) {}
    }

    for (int i = 0; i < _items.length; i++) {
      final it = _items[i];
      final cap = _maxQty[_capKey(it.dishUid, it.deliveryDate)];
      if (cap != null && cap >= 0 && it.quantity > cap) {
        final old = _items[i];
        _items[i] = old.copyWith(quantity: cap);
        _recalcTotal();
        try {
          await _cartRepo.setItemQuantity(
            dishUid: it.dishUid,
            deliveryDate: it.deliveryDate,
            targetQuantity: cap,
          );
        } catch (_) {}
      }
    }
    if (mounted) setState(() {});
  }

  Map<String, Map<String, List<CartItemModel>>> _groupByDayChef(
    List<CartItemModel> items,
  ) {
    final dayChef = <String, Map<String, List<CartItemModel>>>{};

    for (final it in items) {
      final day = _yyyyMmDd(it.deliveryDate);
      final chef = (it.chefName.isNotEmpty ? it.chefName : 'Unknown Chef');
      final chefMap = dayChef.putIfAbsent(
        day,
        () => <String, List<CartItemModel>>{},
      );
      (chefMap[chef] ??= []).add(it);
    }

    for (final chefMap in dayChef.values) {
      for (final k in chefMap.keys.toList()) {
        chefMap[k]!.sort((a, b) => a.name.compareTo(b.name));
      }
    }

    final sorted = <String, Map<String, List<CartItemModel>>>{};
    final days = dayChef.keys.toList()..sort((a, b) => a.compareTo(b));
    for (final d in days) {
      final m = dayChef[d]!;
      final chefs = m.keys.toList()..sort((a, b) => a.compareTo(b));
      sorted[d] = {for (final c in chefs) c: m[c]!};
    }
    return sorted;
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final res = await _cartRepo.getCart();
      if (!mounted) return;

      final newItems = res.items;
      final newDateChecked = _computeInitialDateChecks(newItems);

      _items = newItems;

      _dateChecked
        ..clear()
        ..addAll(newDateChecked);

      _recalcTotal();
      await _loadAvailabilitiesAndClamp();

      final newGrouped = _groupByDayChef(newItems);

      if (!mounted) return;

      setState(() {
        _loading = false;
        _dateChecked = newDateChecked;
        grouped = newGrouped;
        _items = newItems;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  List<CartItemModel> get _selectedItems =>
      _items.where((e) => e.isSelected).toList();

  Future<void> _checkout() async {
    if (_selectedItems.isEmpty) return;

    final dayEnabled = _dateChecked.entries.any((e) => e.value == true);
    if (!dayEnabled) {
      showAppSnackBar(
        context,
        'Please turn on a delivery date before checking out.',
        type: SnackBarType.warning, // Gọi trạng thái Warning màu cam
      );
      return;
    }

    setState(() => _posting = true);
    try {
      CustomerAddress? addr = await _customerRepo.getCurrentAddress();
      if (addr == null || addr.id == 0) {
        final all = await _customerRepo.getAllAddresses();
        if (all.isNotEmpty) {
          addr = all.first;
        } else {
          if (!mounted) return;
          showAppSnackBar(
            context,
            'You do not have any delivery address yet.',
            type: SnackBarType.warning, // Gọi trạng thái Warning màu cam
          );
          return;
        }
      }

      final firstDate = _selectedItems.first.deliveryDate;

      final draft = await _cartRepo.checkoutOrder(
        fullName: 'Phuc Nguyen',
        phoneNumber: '0907400561',
        deliveryDate: _yyyyMmDd(firstDate),
        deliveryTime: _defaultDeliveryTime,
        deliveryAddressId: addr.id,
        paymentMethod: 'COD',
        selectedItems: _selectedItems,
      );

      if (!mounted) return;

      if (draft == null || draft.uid.isEmpty) {
        showAppSnackBar(
          context,
          'Failed to create draft order.',
          type: SnackBarType.error, // Gọi trạng thái Warning màu cam
        );
        return;
      }

      final bool? didPlaceOrder = await Navigator.push(
        context,
        MaterialPageRoute(builder: (_) => CheckoutPage(draft: draft)),
      );
      debugPrint("--- CheckoutPage popped with result: $didPlaceOrder ---");
      if (didPlaceOrder == true && context.mounted) {
        debugPrint("--- Result = true, reloading cart via _load() ---");
        _load();
      }
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(
        context,
        'Checkout error: $e',
        type: SnackBarType.error, // Gọi trạng thái Warning màu cam
      );
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  Future<void> _removeItem(CartItemModel item) async {
    final idx = _items.indexWhere((e) => e.uid == item.uid);
    if (idx < 0) return;

    final removedItem = _items[idx];

    setState(() {
      _items.removeAt(idx);
      grouped = _groupByDayChef(_items);
      _recalcTotal();
    });

    final ok = await _cartRepo.removeItem(
      dishUid: item.dishUid,
      deliveryDate: item.deliveryDate.toString(),
    );

    if (!ok && mounted) {
      setState(() {
        _items.insert(idx, removedItem);
        grouped = _groupByDayChef(_items);
        _recalcTotal();
      });
      showAppSnackBar(
        context,
        'Failed to remove item from cart.',
        type: SnackBarType.error, // Gọi trạng thái Warning màu cam
      );
    }
  }

  int? _capFor(CartItemModel it) =>
      _maxQty[_capKey(it.dishUid, it.deliveryDate)];

  // Hàm hiển thị Popup nhập số lượng tay
  Future<void> _showEditQuantityDialog(CartItemModel item) async {
    // Khởi tạo controller với số lượng hiện tại
    final TextEditingController qtyController =
        TextEditingController(text: item.quantity.toString());

    final int? newQuantity = await showDialog<int>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          backgroundColor: Colors.white,
          title: const Text("Nhập số lượng",
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
          content: TextField(
            controller: qtyController,
            keyboardType: TextInputType.number, // Mở bàn phím số
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly
            ], // Chỉ cho nhập số
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            decoration: InputDecoration(
              focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: _kPrimaryPink, width: 2)),
              enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: Colors.grey.shade300)),
              contentPadding: const EdgeInsets.symmetric(vertical: 16),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context), // Hủy
              child: const Text("Hủy",
                  style: TextStyle(
                      color: Colors.grey, fontWeight: FontWeight.bold)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: _kPrimaryPink,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: () {
                // Ép kiểu chuỗi gõ vào thành số nguyên, nếu rỗng thì cho bằng 0
                final val = int.tryParse(qtyController.text) ?? 0;
                Navigator.pop(context, val);
              },
              child: const Text("Cập nhật",
                  style: TextStyle(
                      color: Colors.white, fontWeight: FontWeight.bold)),
            ),
          ],
        );
      },
    );
    qtyController.dispose();

    // Nếu người dùng bấm Cập nhật và số lượng có thay đổi
    if (newQuantity != null && newQuantity != item.quantity) {
      // Gọi lại hàm xịn sò của bạn, nó sẽ tự lo việc xóa (nếu = 0) hoặc báo lỗi nếu vượt quá Max
      _changeQtyOptimistic(item, newQuantity);
    }
  }

  Future<void> _changeQtyOptimistic(CartItemModel it, int toQty) async {
    if (toQty == 0) {
      await _removeItem(it);
      return;
    }

    final idx = _items.indexWhere((e) => e.uid == it.uid);
    if (idx < 0) return;

    final cap = _capFor(it);
    if (cap != null && toQty > cap) {
      showAppSnackBar(
        context,
        'Maximum $cap servings allowed on ${_yyyyMmDd(it.deliveryDate)}.',
        type:
            SnackBarType.warning, // Cảnh báo nhẹ nhàng để user tự giảm số lượng
      );
      return;
    }

    final old = _items[idx];

    setState(() {
      final index = _items.indexWhere((item) => item.uid == it.uid);
      if (index != -1) {
        _items[idx] = old.copyWith(quantity: toQty);
      }
      grouped = _groupByDayChef(_items);
      _recalcTotal();
    });

    final ok = await _cartRepo.setItemQuantity(
      dishUid: it.dishUid,
      deliveryDate: it.deliveryDate,
      targetQuantity: toQty,
    );

    if (!ok && mounted) {
      setState(() {
        _items[idx] = old;
        _recalcTotal();
      });
      showAppSnackBar(
        context,
        'Failed to update quantity.',
        type: SnackBarType.error, // Cảnh báo nhẹ nhàng để user tự giảm số lượng
      );
    }
  }

  Widget _buildImagePlaceholder() {
    return Container(
      color: _kLightPinkLine,
      width: 70,
      height: 70,
      child:
          Icon(Icons.fastfood, color: _kPrimaryPink.withOpacity(0.5), size: 30),
    );
  }

  Future<void> _toggleSelect(CartItemModel item, String dayKey) async {
    final wantSelected = !item.isSelected;
    final canSelect = _dateChecked[dayKey] == true;

    if (!canSelect && wantSelected) return;

    final idx = _items.indexWhere((e) => e.uid == item.uid);
    if (idx < 0) return;

    setState(() {
      _items[idx] = _items[idx].copyWith(isSelected: wantSelected);
      _recalcTotal();

      grouped = _groupByDayChef(_items);
    });

    await _ensureToggleOnServer(item, wantSelected);
  }

  Future<void> _ensureToggleOnServer(
    CartItemModel item,
    bool wantSelected,
  ) async {
    final ok = await _cartRepo.toggleSelect(item.uid);
    if (!ok && mounted) {
      final idx = _items.indexWhere((e) => e.uid == item.uid);
      if (idx >= 0) {
        setState(() {
          _items[idx] = _items[idx].copyWith(isSelected: !wantSelected);
          _recalcTotal();

          grouped = _groupByDayChef(_items);
        });
      }
      showAppSnackBar(
        context,
        'Failed to update selection status.',
        type: SnackBarType.error, // Cảnh báo nhẹ nhàng để user tự giảm số lượng
      );
    }
  }

  Future<void> _toggleDate(String dateKey, bool value) async {
    final toSync = <CartItemModel, bool>{};

    setState(() {
      if (value) {
        _dateChecked.updateAll((_, __) => false);
        _dateChecked[dateKey] = true;

        for (int i = 0; i < _items.length; i++) {
          final sameDay = _yyyyMmDd(_items[i].deliveryDate) == dateKey;
          final target = sameDay;
          if (_items[i].isSelected != target) {
            toSync[_items[i]] = target;
            _items[i] = _items[i].copyWith(isSelected: target);
          }
        }
      } else {
        _dateChecked[dateKey] = false;
        for (int i = 0; i < _items.length; i++) {
          final sameDay = _yyyyMmDd(_items[i].deliveryDate) == dateKey;
          if (sameDay && _items[i].isSelected) {
            toSync[_items[i]] = false;
            _items[i] = _items[i].copyWith(isSelected: false);
          }
        }
      }
      grouped = _groupByDayChef(_items);
      _recalcTotal();
    });

    for (final e in toSync.entries) {
      await _ensureToggleOnServer(e.key, e.value);
    }
  }
}
