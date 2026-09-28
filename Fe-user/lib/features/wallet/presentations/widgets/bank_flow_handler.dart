import 'package:flutter/material.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/wallet/models/customer_bank.dart';
import 'package:testing/features/wallet/repositories/wallet_repository.dart';

class BankFlowHandler {
  final WalletRepository _walletRepo = WalletRepository();
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);

  // Hàm khởi động luồng: Gọi hàm này khi nhấn nút từ Wallet Page
  void startCreateBankFlow(BuildContext context, VoidCallback onFlowCompleted) {
    _showUpsertBankDialog(context, onFlowCompleted);
  }

  void _showUpsertBankDialog(BuildContext context, VoidCallback onFlowCompleted) {
    // Đưa list bank vào trong hàm (hoặc bạn có thể để làm biến toàn cục của class)
    final List<Map<String, String>> vietnamBanks = [
      {"name": "Vietcombank", "code": "970436"},
      {"name": "Techcombank", "code": "970422"},
      {"name": "VPBank", "code": "970415"},
      {"name": "BIDV", "code": "970405"},
      {"name": "Agribank", "code": "970402"},
      {"name": "MBBank", "code": "970401"},
      {"name": "ACB", "code": "970400"},
      {"name": "Sacombank", "code": "970403"},
      {"name": "VietinBank", "code": "970404"},
      {"name": "TPBank", "code": "970406"},
      {"name": "HDBank", "code": "970407"},
      {"name": "VIB", "code": "970408"},
    ];

    // Thay thế nameController và codeController bằng 2 biến state
    String? selectedBankName;
    String? selectedBankCode;
    
    final numController = TextEditingController();
    final accNameController = TextEditingController();
    final branchController = TextEditingController();
    bool isSubmitting = false;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext dialogContext) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              title: Text("Link Bank Account", style: TextStyle(fontWeight: FontWeight.bold, color: _textBrown)),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // 💡 TECH LEAD FIX: Dropdown chọn Ngân hàng
                    DropdownButtonFormField<String>(
                      decoration: InputDecoration(
                        labelText: "Select Bank",
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
                      ),
                      value: selectedBankName,
                      hint: const Text("Choose your bank"),
                      items: vietnamBanks.map((bank) {
                        return DropdownMenuItem<String>(
                          value: bank["name"],
                          child: Text(bank["name"]!, style: const TextStyle(fontSize: 14)),
                        );
                      }).toList(),
                      onChanged: (String? newValue) {
                        if (newValue != null) {
                          setDialogState(() {
                            selectedBankName = newValue;
                            // Tự động tìm và gán bank code tương ứng
                            selectedBankCode = vietnamBanks.firstWhere((b) => b["name"] == newValue)["code"];
                          });
                        }
                      },
                    ),
                    const SizedBox(height: 12),
                    
                    TextField(
                      controller: numController, 
                      keyboardType: TextInputType.number, 
                      decoration: InputDecoration(labelText: "Account Number", border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)))
                    ),
                    const SizedBox(height: 12),
                    
                    TextField(
                      controller: accNameController, 
                      decoration: InputDecoration(labelText: "Account Holder Name", border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)))
                    ),
                    const SizedBox(height: 12),
                    
                    TextField(
                      controller: branchController, 
                      decoration: InputDecoration(labelText: "Bank Branch (Optional)", border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)))
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: isSubmitting ? null : () => Navigator.pop(dialogContext),
                  child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
                ),
                ElevatedButton(
                  onPressed: isSubmitting
                      ? null
                      : () async {
                          // Validate dữ liệu chặt chẽ hơn
                          if (selectedBankName == null || selectedBankCode == null) {
                            showAppSnackBar(context, "Please select a bank", type: SnackBarType.warning);
                            return;
                          }
                          if (numController.text.isEmpty || accNameController.text.isEmpty) {
                            showAppSnackBar(context, "Please fill all required fields", type: SnackBarType.warning);
                            return;
                          }

                          setDialogState(() => isSubmitting = true);

                          try {
                            // Truyền 2 biến State thay vì lấy từ Controller cũ
                            final tempBank = CustomerBank(
                              bankName: selectedBankName!,
                              bankCode: selectedBankCode!,
                              accountNumber: numController.text.trim(),
                              accountName: accNameController.text.trim().toUpperCase(),
                              bankBranch: branchController.text.trim(),
                              isVerify: false,
                            );

                            final result = await _walletRepo.upsertCustomerBankInfo(tempBank);
                            
                            Navigator.pop(dialogContext);
                            showAppSnackBar(context, result.message, type: SnackBarType.success);

                            _showVerifyOtpDialog(context, result.resetSessionToken, onFlowCompleted);

                          } catch (e) {
                            setDialogState(() => isSubmitting = false);
                            showAppSnackBar(context, e.toString(), type: SnackBarType.error);
                          }
                        },
                  style: ElevatedButton.styleFrom(backgroundColor: _primaryRed),
                  child: isSubmitting
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Text("Next", style: TextStyle(color: Colors.white)),
                ),
              ],
            );
          },
        );
      },
    ).whenComplete(() {
      numController.dispose();
      accNameController.dispose();
      branchController.dispose();
    });
  }

  // 2️⃣ BƯỚC 2: Dialog nhập mã OTP xác thực (Verify OTP)
  void _showVerifyOtpDialog(BuildContext context, String sessionToken, VoidCallback onFlowCompleted) {
    final otpController = TextEditingController();
    bool isSubmitting = false;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext dialogContext) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              title: Text("Verify Email OTP", style: TextStyle(fontWeight: FontWeight.bold, color: _textBrown)),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text("An OTP code has been sent to your registered email. Please check and enter it below:", style: TextStyle(fontSize: 14, height: 1.3)),
                  const SizedBox(height: 16),
                  TextField(
                    controller: otpController,
                    keyboardType: TextInputType.number,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: 8),
                    decoration: InputDecoration(
                      hintText: "000000",
                      hintStyle: const TextStyle(color: Colors.grey, letterSpacing: 0),
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: isSubmitting ? null : () => Navigator.pop(dialogContext),
                  child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
                ),
                ElevatedButton(
                  onPressed: isSubmitting
                      ? null
                      : () async {
                          if (otpController.text.trim().isEmpty) return;

                          setDialogState(() => isSubmitting = true);

                          try {
                            // Gọi API bước 2: Xác thực mã OTP
                            final verifiedBank = await _walletRepo.verifyBankInfoOtp(
                              resetSessionToken: sessionToken,
                              otp: otpController.text.trim(),
                            );

                            // Tắt Dialog OTP
                            Navigator.pop(dialogContext);
                            showAppSnackBar(context, "Bank account linked successfully!", type: SnackBarType.success);

                            // Kích hoạt callback thông báo cho Wallet Page load lại danh sách mới nhất
                            onFlowCompleted();

                          } catch (e) {
                            setDialogState(() => isSubmitting = false);
                            showAppSnackBar(context, "Invalid OTP: $e", type: SnackBarType.error);
                          }
                        },
                  style: ElevatedButton.styleFrom(backgroundColor: _textBrown),
                  child: isSubmitting
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Text("Verify & Complete", style: TextStyle(color: Colors.white)),
                ),
              ],
            );
          },
        );
      },
    ).whenComplete(otpController.dispose);
  }
}
