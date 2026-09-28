// 📂 features/wallet/pages/chef_add_bank_page.dart
import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/wallet/models/bank_constants.dart';
import 'package:testing/features/wallet/repositories/wallet_repository.dart';

class ChefAddBankPage extends StatefulWidget {
  const ChefAddBankPage({super.key});

  @override
  State<ChefAddBankPage> createState() => _ChefAddBankPageState();
}

class _ChefAddBankPageState extends State<ChefAddBankPage> {
  // --- Styling colors ---
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _inputYellow = const Color(0xFFF9F0C2);

  final WalletRepository _walletRepo = WalletRepository();

  // --- Controllers ---
  final _accNumberCtrl = TextEditingController();
  final _accNameCtrl = TextEditingController();
  final _branchCtrl = TextEditingController();
  final _citizenIdCtrl = TextEditingController();
  final _taxCodeCtrl = TextEditingController();

  // --- Bank Dropdown State ---
  LocalBank? _selectedBank;
  
  bool _isSubmitting = false;

  @override
  void dispose() {
    _accNumberCtrl.dispose();
    _accNameCtrl.dispose();
    _branchCtrl.dispose();
    _citizenIdCtrl.dispose();
    _taxCodeCtrl.dispose();
    super.dispose();
  }

  void _submit() async {
    // 1. Validate các trường bắt buộc
    if (_selectedBank == null) {
      showAppSnackBar(context, "Please select a bank", type: SnackBarType.warning);
      return;
    }
    if (_accNumberCtrl.text.trim().isEmpty || _accNameCtrl.text.trim().isEmpty) {
      showAppSnackBar(context, "Account Number and Name are required", type: SnackBarType.warning);
      return;
    }

    setState(() => _isSubmitting = true);

    try {
      // 2. Build Payload gửi API
      final payload = {
        "bank_name": _selectedBank!.apiEnum, // Gửi Enum chuẩn (VD: "Vietcombank")
        "bank_code": _selectedBank!.code,
        "bank_account_number": _accNumberCtrl.text.trim(),
        "bank_account_name": _accNameCtrl.text.trim().toUpperCase(),
        "bank_branch": _branchCtrl.text.trim().isEmpty ? null : _branchCtrl.text.trim(),
        "citizen_id": _citizenIdCtrl.text.trim().isEmpty ? null : _citizenIdCtrl.text.trim(),
        "tax_code": _taxCodeCtrl.text.trim().isEmpty ? null : _taxCodeCtrl.text.trim(),
      };

      // 3. Gọi API Upsert
      final success = await _walletRepo.upsertChefPaymentInfo(payload);

      if (mounted) {
        if (success) {
          showAppSnackBar(context, "Bank account linked successfully!", type: SnackBarType.success);
          Navigator.pop(context, true); // Trả về true báo hiệu thành công
        } else {
          showAppSnackBar(context, "Failed to link bank account.", type: SnackBarType.error);
        }
      }
    } catch (e) {
      if (mounted) {
        showAppSnackBar(context, e.toString(), type: SnackBarType.error);
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          // ===== HEADER =====
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 60, 20, 20),
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left, color: Colors.black, size: 30),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                const Expanded(
                  child: Text(
                    "Link Bank Account",
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold),
                  ),
                ),
                const SizedBox(width: 48), // Cân bằng không gian
              ],
            ),
          ),

          // ===== BODY FORM =====
          Expanded(
            child: Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.only(topLeft: Radius.circular(30), topRight: Radius.circular(30)),
              ),
              child: SingleChildScrollView(
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text("Enter your bank details to receive payouts from orders.", style: TextStyle(color: Colors.grey, fontSize: 14)),
                    const SizedBox(height: 24),

                    // --- Bank Name Dropdown ---
                    _buildLabel("Select Bank *"),
                    const SizedBox(height: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                      decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(12)),
                      child: DropdownButtonHideUnderline(
                        child: DropdownButton<LocalBank>( // 💡 Type-Safety: Ép kiểu thành object LocalBank
                          value: _selectedBank,
                          hint: const Text("Choose your bank", style: TextStyle(color: Colors.black38)),
                          isExpanded: true,
                          icon: const Icon(Icons.keyboard_arrow_down, color: Colors.black54),
                          items: vietnamBanks.map((LocalBank bank) {
                            return DropdownMenuItem<LocalBank>(
                              value: bank,
                              child: Text(bank.displayName, style: const TextStyle(color: Colors.black87, fontSize: 15)), // Hiển thị tên đẹp
                            );
                          }).toList(),
                          onChanged: (val) {
                            if (val != null) setState(() => _selectedBank = val);
                          },
                        ),
                      ),
                    ),
                    const SizedBox(height: 20),

                    // --- Account Number ---
                    _buildLabel("Account Number *"),
                    const SizedBox(height: 8),
                    _buildInput(_accNumberCtrl, "E.g. 1030959013", isNumber: true),
                    const SizedBox(height: 20),

                    // --- Account Name ---
                    _buildLabel("Account Holder Name *"),
                    const SizedBox(height: 8),
                    _buildInput(_accNameCtrl, "E.g. PHAM MINH PHUC"),
                    const SizedBox(height: 20),

                    // --- Optional Fields ---
                    const Divider(height: 40),
                    const Text("Additional Information (Optional)", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.grey)),
                    const SizedBox(height: 16),

                    _buildLabel("Bank Branch"),
                    const SizedBox(height: 8),
                    _buildInput(_branchCtrl, "E.g. Chi nhanh Tan Binh"),
                    const SizedBox(height: 16),

                    Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Citizen ID"),
                              const SizedBox(height: 8),
                              _buildInput(_citizenIdCtrl, "CCCD", isNumber: true),
                            ],
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildLabel("Tax Code"),
                              const SizedBox(height: 8),
                              _buildInput(_taxCodeCtrl, "MST", isNumber: true),
                            ],
                          ),
                        ),
                      ],
                    ),
                    
                    const SizedBox(height: 40),
                    
                    // --- Submit Button ---
                    SizedBox(
                      width: double.infinity,
                      height: 52,
                      child: ElevatedButton(
                        onPressed: _isSubmitting ? null : _submit,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: _primaryRed,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(26)),
                          elevation: 2,
                        ),
                        child: _isSubmitting 
                          ? const SizedBox(height: 24, width: 24, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                          : const Text("Save & Link Account", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white)),
                      ),
                    ),
                    const SizedBox(height: 30),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
  
  // --- UI Helpers ---
  Widget _buildLabel(String text) {
    return Text(text, style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textBrown));
  }
  
  Widget _buildInput(TextEditingController ctrl, String hint, {bool isNumber = false}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      decoration: BoxDecoration(color: _inputYellow, borderRadius: BorderRadius.circular(12)),
      child: TextField(
        controller: ctrl,
        keyboardType: isNumber ? TextInputType.number : TextInputType.text,
        textCapitalization: isNumber ? TextCapitalization.none : TextCapitalization.characters, // Tự động viết hoa tên
        style: const TextStyle(color: Colors.black87, fontSize: 15),
        decoration: InputDecoration(
          border: InputBorder.none,
          hintText: hint,
          isDense: true,
          hintStyle: const TextStyle(color: Colors.black38),
        ),
      ),
    );
  }
}