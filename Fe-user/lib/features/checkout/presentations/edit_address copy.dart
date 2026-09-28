import 'package:flutter/material.dart';
import '../models/address_item.dart';
import '../models/order_draft.dart';
import 'add_address.dart';
import '../../common/app_components.dart';
import '../repositories/edit_order_repository.dart';

/// Chọn địa chỉ giao hàng (load từ BE) & gắn vào Checkout (OrderDraft)
class EditAddressPage extends StatefulWidget {
  const EditAddressPage({
    super.key,
    required this.orderUid,
    required this.initialAddressId,
  });

  final String orderUid;
  final int? initialAddressId;

  @override
  State<EditAddressPage> createState() => _EditAddressPageState();
}

class _EditAddressPageState extends State<EditAddressPage> {
  static const _bgOrange = Color(0xFFFFBB94);
  static const _accent   = Color(0xFFE84D67);

  final _repo = EditOrderRepository();

  bool _loading = true;
  bool _saving  = false;
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

      // chọn sẵn: initial -> selected=true -> first
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
      // ✅ repo mới: /api/checkouts/{uid}/delivery-address/{address_id}
      final OrderDraft updated = await _repo.setOrderDeliveryAddress(
        uid: widget.orderUid,
        addressId: _selectedId!,
      );
      if (!mounted) return;

      showAppSnackBar(
        context,
        'Address updated successfully!',
        type: SnackBarType.success, // Tự động có icon check, màu xanh, bo góc và nổi lên
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
        type: SnackBarType.error, // Tự động có icon check, màu xanh, bo góc và nổi lên
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
    final hasSelection = _selectedId != null && _selectedId != 0;

    return Scaffold(
      backgroundColor: _bgOrange,
      body: Column(
        children: [
          // Header
          Container(
            color: _bgOrange,
            padding: const EdgeInsets.fromLTRB(6, 8, 12, 12),
            child: Row(
              children: [
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.arrow_back, color: Colors.black),
                ),
                const Expanded(
                  child: Center(
                    child: Text(
                      'Delivery Address',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                    ),
                  ),
                ),
                const SizedBox(width: 40),
              ],
            ),
          ),

          // Sheet
          Expanded(
            child: Container(
              width: double.infinity,
              margin: const EdgeInsets.only(top: 10),
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(26),
                  topRight: Radius.circular(26),
                ),
              ),
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                      ? Center(child: Text(_error!))
                      : _list.isEmpty
                          ? _emptyState()
                          : Column(
                              children: [
                                Expanded(
                                  child: ListView.separated(
                                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                                    itemCount: _list.length,
                                    separatorBuilder: (_, __) => const Divider(
                                      height: 24,
                                      thickness: 1,
                                      color: Color(0xFFEFB7C0),
                                    ),
                                    itemBuilder: (_, i) => _addressTile(_list[i]),
                                  ),
                                ),
                                Padding(
                                  padding: const EdgeInsets.fromLTRB(16, 6, 16, 18),
                                  child: Row(
                                    children: [
                                      Expanded(
                                        child: OutlinedButton(
                                          onPressed: _saving ? null : _onAddNew,
                                          style: OutlinedButton.styleFrom(
                                            side: const BorderSide(color: _accent),
                                            foregroundColor: _accent,
                                            padding: const EdgeInsets.symmetric(vertical: 12),
                                            shape: RoundedRectangleBorder(
                                              borderRadius: BorderRadius.circular(22),
                                            ),
                                          ),
                                          child: const Text(
                                            'Add New Address',
                                            style: TextStyle(fontWeight: FontWeight.w700),
                                          ),
                                        ),
                                      ),
                                      const SizedBox(width: 12),
                                      Expanded(
                                        child: ElevatedButton(
                                          onPressed: (!hasSelection || _saving) ? null : _confirm,
                                          style: ElevatedButton.styleFrom(
                                            backgroundColor: _accent,
                                            disabledBackgroundColor: _accent.withOpacity(0.4),
                                            padding: const EdgeInsets.symmetric(vertical: 12),
                                            shape: RoundedRectangleBorder(
                                              borderRadius: BorderRadius.circular(22),
                                            ),
                                          ),
                                          child: _saving
                                              ? const SizedBox(
                                                  width: 18,
                                                  height: 18,
                                                  child: CircularProgressIndicator(
                                                    strokeWidth: 2,
                                                    valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                                                  ),
                                                )
                                              : const Text(
                                                  'Confirm',
                                                  style: TextStyle(
                                                    color: Colors.white,
                                                    fontWeight: FontWeight.w700,
                                                  ),
                                                ),
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _addressTile(AddressItem a) {
    final selected = a.id == _selectedId;
    return InkWell(
      onTap: () => setState(() => _selectedId = a.id),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.location_on_rounded, color: _accent, size: 28),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              a.fullAddress,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
            ),
          ),
          const SizedBox(width: 12),
          Icon(
            selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
            color: selected ? _accent : Colors.black45,
          ),
        ],
      ),
    );
  }

  Widget _emptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.location_off, size: 48, color: Colors.black45),
            const SizedBox(height: 12),
            const Text(
              'No addresses yet',
              style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
            ),
            const SizedBox(height: 6),
            const Text(
              'Add a shipping address to continue.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.black54),
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _saving ? null : _onAddNew,
              style: ElevatedButton.styleFrom(
                backgroundColor: _accent,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
              ),
              child: const Text('Add New Address',
                  style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
  }
}
