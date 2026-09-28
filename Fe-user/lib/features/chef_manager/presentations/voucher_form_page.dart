import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:testing/features/common/app_components.dart';
import '../repositories/voucher_repository.dart';
import '../models/voucher_model.dart';

class VoucherFormPage extends StatefulWidget {
  final VoucherModel? editVoucher;

  const VoucherFormPage({super.key, this.editVoucher});

  @override
  State<VoucherFormPage> createState() => _ChefVoucherFormPageState();
}

class _ChefVoucherFormPageState extends State<VoucherFormPage> {
  // --- COLORS (Matching the pattern) ---
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _inputYellow = const Color(0xFFF9F0C2);

  bool get isEdit => widget.editVoucher != null;
  bool _isLoading = false;

  // --- CONTROLLERS & STATES ---
  final TextEditingController _codeCtrl = TextEditingController();
  final TextEditingController _nameCtrl = TextEditingController();
  final TextEditingController _descCtrl = TextEditingController();
  final TextEditingController _discountCtrl = TextEditingController();
  final TextEditingController _minOrderCtrl = TextEditingController(text: "0");

  String _discountType = "FIXED_AMOUNT"; 
  DateTime? _startDate;
  DateTime? _endDate;

  @override
  void initState() {
    super.initState();
    if (isEdit) {
      final v = widget.editVoucher!;
      _codeCtrl.text = v.code;
      _nameCtrl.text = v.name;
      _descCtrl.text = v.description;
      
      _discountCtrl.text = v.discountValue.toStringAsFixed(v.discountValue.truncateToDouble() == v.discountValue ? 0 : 2);
      _minOrderCtrl.text = v.minOrderAmount.toStringAsFixed(v.minOrderAmount.truncateToDouble() == v.minOrderAmount ? 0 : 2);
      
      _discountType = v.discountType;
      _startDate = v.startDate;
      _endDate = v.endDate;
    }
  }

  @override
  void dispose() {
    _codeCtrl.dispose();
    _nameCtrl.dispose();
    _descCtrl.dispose();
    _discountCtrl.dispose();
    _minOrderCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickDate(bool isStart) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: DateTime.now(),
      firstDate: DateTime.now(),
      lastDate: DateTime(2030),
      builder: (context, child) => Theme(
        data: Theme.of(context).copyWith(
          colorScheme: ColorScheme.light(primary: _primaryOrange, onPrimary: Colors.white),
        ),
        child: child!,
      ),
    );

    if (picked != null) {
      setState(() {
        if (isStart) {
          _startDate = picked;
          if (_endDate != null && _endDate!.isBefore(_startDate!)) _endDate = null;
        } else {
          if (_startDate != null && picked.isBefore(_startDate!)) {
            showAppSnackBar(
              context,
              "End date must be after start date",
              type: SnackBarType.warning,
            );
            return;
          }
          _endDate = picked;
        }
      });
    }
  }

  Future<void> _submit() async {
    if (_codeCtrl.text.isEmpty || _nameCtrl.text.isEmpty || _descCtrl.text.isEmpty || _discountCtrl.text.isEmpty) {
      showAppSnackBar(
        context,
        "Please fill all required fields!",
        type: SnackBarType.warning,
      );
      return;
    }
    if (_startDate == null || _endDate == null) {
      showAppSnackBar(
        context,
        "Please select start and end dates!",
        type: SnackBarType.warning,
      );
      return;
    }

    final repo = VoucherRepository();
    double discountVal = double.tryParse(_discountCtrl.text) ?? 0;
    double minOrderVal = double.tryParse(_minOrderCtrl.text) ?? 0;

    setState(() => _isLoading = true);

    bool success = false;

    if (isEdit) {
      success = await repo.updateVoucher(
        widget.editVoucher!.uid,
        name: _nameCtrl.text.trim(),
        description: _descCtrl.text.trim(),
        discountValue: discountVal,
        minOrderAmount: minOrderVal,
        startDate: _startDate!,
        endDate: _endDate!,
      );
    } else {
      success = await repo.createVoucher(
        code: _codeCtrl.text.trim().toUpperCase(),
        name: _nameCtrl.text.trim(),
        description: _descCtrl.text.trim(),
        discountType: _discountType,
        discountValue: discountVal,
        minOrderAmount: minOrderVal,
        startDate: _startDate!,
        endDate: _endDate!,
      );
    }
  
    if (mounted) {
      setState(() => _isLoading = false);
      if (success) {
        showAppSnackBar(
          context,
          isEdit ? "Voucher updated successfully!" : "Voucher created!",
          type: SnackBarType.success,
        );
        Navigator.pop(context, true);
      } else {
        showAppSnackBar(
          context,
          "Failed to create/update voucher. Please try again.",
          type: SnackBarType.error,
        );
      }
    }
  }

  void _showDeleteConfirmDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text("Delete Voucher?"),
        content: const Text("Are you sure you want to delete this voucher? This action cannot be undone."),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              Navigator.pop(ctx);
              setState(() => _isLoading = true);

              final repo = VoucherRepository();
              final success = await repo.deleteVoucher(widget.editVoucher!.uid);
              
              if (mounted) {
                setState(() => _isLoading = false);
                if (success) {
                  showAppSnackBar(
                    context,
                    "Voucher deleted successfully!",
                    type: SnackBarType.success,
                  );
                  Navigator.pop(context, true);
                } else {
                  showAppSnackBar(
                    context,
                    "Failed to delete voucher. Please try again.",
                    type: SnackBarType.error,
                  );
                }
              }
            },
            child: const Text("Delete", style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER WITH PATTERN =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                // Back button
                IconButton(
                  icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                
                // Title in center
                Expanded(
                  child: Text(
                    isEdit ? "Edit Voucher" : "Add New Voucher",
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                
                // Delete button for edit mode
                if (isEdit)
                  IconButton(
                    icon: const Icon(Icons.delete_outline, color: Colors.white, size: 28),
                    onPressed: _showDeleteConfirmDialog,
                  )
                else
                  const SizedBox(width: 48),
              ],
            ),
          ),

          // ===== BODY WITH WHITE CARD =====
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
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // If Edit mode - show voucher code
                    if (isEdit) ...[
                      Text(
                        "#${widget.editVoucher!.code}",
                        style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: _textBrown),
                      ),
                      const SizedBox(height: 20),
                    ] else ...[
                      // Add mode - show Code & Name inputs
                      Row(
                        children: [
                          Expanded(child: _buildInputField("Code *", _codeCtrl, hint: "SUMMER25")),
                          const SizedBox(width: 15),
                          Expanded(child: _buildInputField("Name *", _nameCtrl, hint: "Summer Sale")),
                        ],
                      ),
                      const SizedBox(height: 15),
                    ],

                    // Voucher Type Dropdown
                    _buildLabel("Voucher Type *"),
                    const SizedBox(height: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 15),
                      decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(10)),
                      child: DropdownButtonHideUnderline(
                        child: DropdownButton<String>(
                          value: _discountType,
                          isExpanded: true,
                          items: const [
                            DropdownMenuItem(value: "FIXED_AMOUNT", child: Text("Fixed Amount (\$ / VND)")),
                            DropdownMenuItem(value: "PERCENTAGE", child: Text("Percentage (%)")),
                          ],
                          onChanged: (val) => setState(() => _discountType = val!),
                        ),
                      ),
                    ),
                    const SizedBox(height: 15),

                    // Discount & Min Order Input
                    Row(
                      children: [
                        Expanded(child: _buildInputField(
                          "Discount *", 
                          _discountCtrl, 
                          hint: _discountType == "PERCENTAGE" ? "E.g. 15" : "E.g. 5.000", 
                          isNumber: true
                        )),
                        const SizedBox(width: 15),
                        Expanded(child: _buildInputField(
                          "Min Order", 
                          _minOrderCtrl, 
                          hint: "E.g. 100.000", 
                          isNumber: true
                        )),
                      ],
                    ),
                    const SizedBox(height: 15),

                    // Description Input
                    _buildLabel("Description *"),
                    const SizedBox(height: 8),
                    Container(
                      decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(10)),
                      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 5),
                      child: TextField(
                        controller: _descCtrl,
                        maxLines: 3,
                        decoration: const InputDecoration(
                          border: InputBorder.none,
                          hintText: "Write a description for the voucher.",
                          hintStyle: TextStyle(color: Colors.black54),
                        ),
                      ),
                    ),
                    const SizedBox(height: 15),

                    // Date Inputs
                    Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Start date *"),
                              const SizedBox(height: 8),
                              _buildDatePicker(
                                _startDate == null ? "DD.MM.YY" : DateFormat('dd.MM.yyyy').format(_startDate!),
                                () => _pickDate(true),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 15),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("End date *"),
                              const SizedBox(height: 8),
                              _buildDatePicker(
                                _endDate == null ? "DD.MM.YY" : DateFormat('dd.MM.yyyy').format(_endDate!),
                                () => _pickDate(false),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    
                    const SizedBox(height: 100),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
      // Bottom Action Button
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
      floatingActionButton: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: SizedBox(
          width: double.infinity,
          height: 50,
          child: ElevatedButton(
            onPressed: _isLoading ? null : _submit,
            style: ElevatedButton.styleFrom(
              backgroundColor: _primaryRed,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
            ),
            child: _isLoading 
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
              : Text(
                  isEdit ? "Apply" : "Add", 
                  style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)
              ),
          ),
        ),
      ),
    );
  }

  // --- UI Helpers ---
  Widget _buildLabel(String text) {
    return Text(
      text,
      style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown),
    );
  }

  Widget _buildInputField(String label, TextEditingController ctrl, {String hint = "", bool isNumber = false}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildLabel(label),
        const SizedBox(height: 8),
        Container(
          decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(10)),
          padding: const EdgeInsets.symmetric(horizontal: 15),
          child: TextField(
            controller: ctrl,
            keyboardType: isNumber ? const TextInputType.numberWithOptions(decimal: true) : TextInputType.text,
            decoration: InputDecoration(
              border: InputBorder.none,
              hintText: hint,
              hintStyle: const TextStyle(color: Colors.black38, fontSize: 13),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildDatePicker(String hint, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12),
        decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(10)),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(hint, style: const TextStyle(fontSize: 14, color: Colors.black87)),
            const Icon(Icons.calendar_month_outlined, color: Colors.black87, size: 20),
          ],
        ),
      ),
    );
  }
}
