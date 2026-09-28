import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'voucher_form_page.dart';
import '../models/voucher_model.dart';
import '../repositories/voucher_repository.dart';

class ChefVoucherListPage extends StatefulWidget {
  const ChefVoucherListPage({super.key});

  @override
  State<ChefVoucherListPage> createState() => _ChefVoucherListPageState();
}

class _ChefVoucherListPageState extends State<ChefVoucherListPage> {
  // Styling colors
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  
  final VoucherRepository _repo = VoucherRepository();
  String _selectedTab = "All";
  List<VoucherModel> _vouchers = [];
  bool _isLoading = true;
  final TextEditingController _searchCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadVouchers();
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadVouchers() async {
    setState(() => _isLoading = true);
    
    final data = await _repo.getAllVouchers();
    
    // Filter by tab
    var filteredData = data;
    if (_selectedTab == "Active") {
      filteredData = filteredData.where((v) => v.isActive).toList();
    } else if (_selectedTab == "Inactive") {
      filteredData = filteredData.where((v) => !v.isActive).toList();
    }
    
    // Filter by search
    if (_searchCtrl.text.isNotEmpty) {
      filteredData = filteredData.where((v) {
        return v.code.toLowerCase().contains(_searchCtrl.text.toLowerCase()) ||
               v.name.toLowerCase().contains(_searchCtrl.text.toLowerCase()) ||
               v.description.toLowerCase().contains(_searchCtrl.text.toLowerCase());
      }).toList();
    }
    
    if (mounted) {
      setState(() {
        _vouchers = filteredData;
        _isLoading = false;
      });
    }
  }

  void _changeTab(String tab) {
    setState(() => _selectedTab = tab);
    _loadVouchers();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Column(
        children: [
          // Search Bar
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Container(
              height: 45,
              decoration: BoxDecoration(
                color: Colors.grey[100],
                borderRadius: BorderRadius.circular(12),
              ),
              child: TextField(
                controller: _searchCtrl,
                onChanged: (_) => _loadVouchers(),
                decoration: const InputDecoration(
                  hintText: "Search vouchers...",
                  prefixIcon: Icon(Icons.search, color: Colors.grey),
                  border: InputBorder.none,
                  contentPadding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ),
          ),
          
          // Custom Tab Bar
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: ["All", "Active", "Inactive"].map((tab) {
                final isSelected = _selectedTab == tab;
                return GestureDetector(
                  onTap: () => _changeTab(tab),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Column(
                      children: [
                        Text(
                          tab,
                          style: TextStyle(
                            color: isSelected ? _primaryRed : Colors.grey,
                            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 4),
                        if (isSelected)
                          Container(
                            height: 2,
                            width: 20,
                            color: _primaryRed,
                          )
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ),

          // List View
          Expanded(
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
              child: _isLoading 
                ? const Center(child: CircularProgressIndicator()) 
                : _vouchers.isEmpty
                    ? const Center(child: Text("No vouchers found. Create one!"))
                    : RefreshIndicator(
                        onRefresh: () async {
                          await _loadVouchers();
                        },
                        child: ListView.builder(
                          itemCount: _vouchers.length,
                          itemBuilder: (context, index) {
                            return _buildVoucherItem(_vouchers[index]);
                          },
                        ),
                      ),
            ),
          ),
        ],
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
      floatingActionButton: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: SizedBox(
          width: double.infinity,
          height: 50,
          child: ElevatedButton(
            onPressed: () async {
              final result = await Navigator.push(
                context, 
                MaterialPageRoute(builder: (_) => const VoucherFormPage())
              );
              
              if (result == true) {
                await _loadVouchers();
              }
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: _primaryRed,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
            ),
            child: const Text("Add new", style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
          ),
        ),
      ),
    );
  }

  // --- UI Item ---
  Widget _buildVoucherItem(VoucherModel voucher) {
    return Container(
      margin: const EdgeInsets.only(bottom: 15),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              InkWell(
                onTap: () => _toggleVoucherStatus(voucher),
                borderRadius: BorderRadius.circular(20),
                child: Container(
                  margin: const EdgeInsets.only(top: 5),
                  padding: const EdgeInsets.all(4),
                  child: Icon(
                    voucher.isActive ? Icons.local_offer_outlined : Icons.remove_circle_outline,
                    color: voucher.isActive ? Colors.green : Colors.red,
                    size: 30,
                  ),
                ),
              ),
              const SizedBox(width: 15),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(voucher.code, style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: _textBrown)),
                        Text("${voucher.formattedStartDate} - ${voucher.formattedEndDate}", style: const TextStyle(fontSize: 11, color: Colors.grey)),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: Text(voucher.description, style: const TextStyle(fontSize: 13, color: Colors.black87), maxLines: 2, overflow: TextOverflow.ellipsis),
                        ),
                        const SizedBox(width: 10),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text("-${voucher.displayDiscount}", style: TextStyle(color: voucher.isActive ? Colors.green : Colors.grey, fontWeight: FontWeight.bold, fontSize: 16)),
                            const SizedBox(height: 5),
                            InkWell(
                              onTap: () async {
                                showDialog(
                                  context: context,
                                  barrierDismissible: false,
                                  builder: (_) => const Center(child: CircularProgressIndicator(color: Colors.orange)),
                                );

                                final detailVoucher = await _repo.getVoucherDetail(voucher.uid);

                                if (context.mounted) Navigator.pop(context);

                                if (detailVoucher != null && context.mounted) {
                                  final result = await Navigator.push(
                                    context, 
                                    MaterialPageRoute(builder: (_) => VoucherFormPage(editVoucher: detailVoucher))
                                  );

                                  if (result == true) {
                                    await _loadVouchers();
                                  }
                                } else {
                                  showAppSnackBar(
                                    context,
                                    "Cannot load voucher detail",
                                    type: SnackBarType.error,
                                  );
                                }
                              },
                              child: const Icon(Icons.edit_note, color: Colors.redAccent, size: 24),
                            )
                          ],
                        )
                      ],
                    )
                  ],
                ),
              )
            ],
          ),
          const SizedBox(height: 10),
          const Divider(height: 1, thickness: 1, color: Colors.redAccent),
        ],
      ),
    );
  }

  Future<void> _toggleVoucherStatus(VoucherModel voucher) async {
    final newStatus = !voucher.isActive;
    
    final success = await _repo.updateVoucher(
      voucher.uid,
      name: voucher.name,
      description: voucher.description,
      discountValue: voucher.discountValue,
      minOrderAmount: voucher.minOrderAmount,
      startDate: voucher.startDate,
      endDate: voucher.endDate,
      isActive: newStatus,
    );

    if (success && mounted) {
      await _loadVouchers(); 
      showAppSnackBar(
        context,
        newStatus ? "Voucher Activated!" : "Voucher Deactivated!",
        type: SnackBarType.success,
      );
    } else if (mounted) {
      showAppSnackBar(
        context,
        "Failed to update status",
        type: SnackBarType.error,
      );
    }
  }
}
