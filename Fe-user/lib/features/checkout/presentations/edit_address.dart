import 'package:flutter/material.dart';
import '../models/address_item.dart';
import '../models/order_draft.dart';
import '../../common/app_components.dart';
import 'add_address.dart';
import '../repositories/edit_order_repository.dart';

class EditAddressPage extends StatefulWidget {
  final String orderUid;
  final int? initialAddressId;
  const EditAddressPage({super.key, required this.orderUid, this.initialAddressId});

  @override
  State<EditAddressPage> createState() => _EditAddressPageState();
}

class _EditAddressPageState extends State<EditAddressPage> {
  // Bảng màu bám sát thiết kế Figma
  final Color _kPeachBg = const Color(0xFFFFC6A6);
  final Color _kPrimaryPink = const Color(0xFFD85C6B);
  final Color _kDarkBrown = const Color(0xFF4A2C2A);
  final Color _kLightPinkLine = const Color(0xFFF3D5D8);

  final _repo = EditOrderRepository();

  bool _loading = true;
  bool _saving = false;
  String? _error;
  List<AddressItem> _list = [];
  int? _selectedId;

  @override
  void initState() {
    super.initState();
    _selectedId = widget.initialAddressId;
    _load();
  }

  Future<void> _load() async {
    setState(() { _loading = true; _error = null; });
    try {
      final items = await _repo.getAllAddresses();
      if (!mounted) return;

      int? preselect = widget.initialAddressId;
      preselect ??= items.where((e) => e.selected).map((e) => e.id).cast<int?>().firstOrNull;
      preselect ??= items.isNotEmpty ? items.first.id : null;

      setState(() {
        _list = items;
        _selectedId = preselect;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = e.toString(); _loading = false; });
    }
  }

  Future<void> _confirm() async {
    if (_saving || _selectedId == null || _selectedId == 0) return;
    setState(() => _saving = true);
    try {
      final OrderDraft updated = await _repo.setOrderDeliveryAddress(
        uid: widget.orderUid,
        addressId: _selectedId!,
      );
      if (!mounted) return;

      showAppSnackBar(
        context,
        'Address updated successfully!', // Đổi nhẹ thành System error cho chuyên nghiệp
        type: SnackBarType.success, 
      );
      Navigator.pop<Map<String, dynamic>>(context, {
        'updated_draft': updated,
        'address_id': _selectedId!,
      });
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(
        context,
        'Update address failed: $e',
        type: SnackBarType.error, 
      );
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _onAddNew() async {
    final created = await Navigator.push<AddressItem?>(
      context,
      MaterialPageRoute(builder: (_) => const AddAddressPage()),
    );
    if (created != null) {
      await _load();
      setState(() => _selectedId = created.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    // === Trạng thái Loading ===
    if (_loading) {
      return Scaffold(
        backgroundColor: _kPeachBg,
        body: const Center(
          child: CircularProgressIndicator(color: Colors.white),
        ),
      );
    }

    // === Trạng thái Lỗi ===
    if (_error != null) {
      return Scaffold(
        backgroundColor: _kPeachBg,
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.arrow_back_ios_new, color: Color(0xFFD85C6B), size: 20),
                      onPressed: () => Navigator.of(context).pop(),
                    ),
                    const Expanded(
                      child: Text("Delivery Address", textAlign: TextAlign.center, style: TextStyle(color: Color(0xFF4A2C2A), fontSize: 18, fontWeight: FontWeight.bold)),
                    ),
                    const SizedBox(width: 48),
                  ],
                ),
              ),
              Expanded(
                child: Container(
                  width: double.infinity,
                  decoration: const BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.vertical(top: Radius.circular(30)),
                  ),
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.error_outline, size: 40, color: Colors.red),
                        const SizedBox(height: 8),
                        Text(_error!, style: const TextStyle(color: Colors.red)),
                        TextButton(onPressed: _load, child: const Text("Retry")),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    }

    // === Giao diện Chính ===
    return Scaffold(
      backgroundColor: _kPeachBg,
      body: SafeArea(
        bottom: false, // Để mảng trắng kéo dài sát cạnh dưới màn hình
        child: Column(
          children: [
            // ===== HEADER =====
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
              child: Row(
                children: [
                  IconButton(
                    icon: Icon(Icons.arrow_back_ios_new, color: _kPrimaryPink, size: 20),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  Expanded(
                    child: Text(
                      "Delivery Address",
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: _kDarkBrown,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(width: 48), // Spacer để cân bằng title
                ],
              ),
            ),

            // ===== BODY =====
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
                child: Column(
                  children: [
                    // --- List Địa chỉ ---
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
                        child: Column(
                          children: [
                            if (_list.isEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 40),
                                child: Text("No addresses found", style: TextStyle(color: Colors.grey.shade400, fontSize: 16)),
                              )
                            else
                              ListView.separated(
                                shrinkWrap: true,
                                physics: const NeverScrollableScrollPhysics(),
                                itemCount: _list.length,
                                separatorBuilder: (_, __) => Divider(color: _kLightPinkLine, thickness: 1, height: 32),
                                itemBuilder: (_, i) {
                                  final item = _list[i];
                                  final isSelected = item.id == _selectedId;
                                  
                                  // Giả lập icon và title giống Figma cho đẹp mắt
                                  String title = "Address ${i + 1}";
                                  IconData iconData = Icons.location_on_outlined;
                                  if (i == 0) {
                                    title = "Home";
                                    iconData = Icons.home_outlined;
                                  } else if (i == 1) {
                                    title = "Company";
                                    iconData = Icons.work_outline;
                                  }

                                  return GestureDetector(
                                    onTap: () => setState(() => _selectedId = item.id),
                                    behavior: HitTestBehavior.opaque, // Giúp vùng bấm nhạy hơn
                                    child: Row(
                                      crossAxisAlignment: CrossAxisAlignment.center,
                                      children: [
                                        Icon(iconData, color: _kPrimaryPink, size: 28),
                                        const SizedBox(width: 16),
                                        Expanded(
                                          child: Column(
                                            crossAxisAlignment: CrossAxisAlignment.start,
                                            children: [
                                              Text(title, style: TextStyle(color: _kDarkBrown, fontWeight: FontWeight.bold, fontSize: 14)),
                                              const SizedBox(height: 4),
                                              Text(
                                                item.fullAddress,
                                                style: TextStyle(color: _kDarkBrown.withOpacity(0.8), fontSize: 13, height: 1.4),
                                              ),
                                            ],
                                          ),
                                        ),
                                        const SizedBox(width: 16),
                                        // Custom Radio Button bám sát Figma
                                        Container(
                                          width: 20,
                                          height: 20,
                                          decoration: BoxDecoration(
                                            shape: BoxShape.circle,
                                            border: Border.all(color: _kPrimaryPink, width: 1.5),
                                          ),
                                          child: isSelected
                                              ? Center(
                                                  child: Container(
                                                    width: 10,
                                                    height: 10,
                                                    decoration: BoxDecoration(
                                                      shape: BoxShape.circle,
                                                      color: _kPrimaryPink,
                                                    ),
                                                  ),
                                                )
                                              : null,
                                        ),
                                      ],
                                    ),
                                  );
                                },
                              ),
                            
                            const SizedBox(height: 32),

                            // --- Nút Add New Address ở giữa màn hình (Chuẩn Figma) ---
                            ElevatedButton(
                              onPressed: _onAddNew,
                              style: ElevatedButton.styleFrom(
                                backgroundColor: _kPrimaryPink,
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(25),
                                ),
                                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                                elevation: 0,
                              ),
                              child: const Text(
                                "Add New Address",
                                style: TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                    // --- Nút Xác Nhận (Giữ nguyên luồng logic API) ---
                    Container(
                      padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        boxShadow: [
                          BoxShadow(color: Colors.black.withOpacity(0.03), blurRadius: 10, offset: const Offset(0, -5))
                        ]
                      ),
                      child: SizedBox(
                        width: double.infinity,
                        height: 50,
                        child: ElevatedButton(
                          onPressed: (_saving || _selectedId == null) ? null : _confirm,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: _kPrimaryPink,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
                            elevation: 0,
                            disabledBackgroundColor: Colors.grey.shade300,
                          ),
                          child: _saving
                              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                              : const Text(
                                  "Confirm Selection",
                                  style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold),
                                ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}