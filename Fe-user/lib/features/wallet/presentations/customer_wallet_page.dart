import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/wallet/models/customer_bank.dart';
import 'package:testing/features/wallet/presentations/widgets/bank_flow_handler.dart';
import 'package:testing/features/wallet/repositories/wallet_repository.dart';

class CustomerWalletPage extends StatefulWidget {
  const CustomerWalletPage({super.key}); // Đã bỏ tham số chefId

  @override
  State<CustomerWalletPage> createState() => _CustomerWalletPageState();
}

class _CustomerWalletPageState extends State<CustomerWalletPage> {
  final WalletRepository _walletRepo = WalletRepository();

  final BankFlowHandler _bankFlowHandler = BankFlowHandler();
  
  // --- Styling ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _infoBlue = const Color(0xFF3498DB);

  // --- State Variables ---
  bool _isLoading = true;
  double _availableBalance = 0;
  double _pendingBalance = 0; // Tiền đang chờ xử lý (VD: Refund đang về)
  CustomerBank? _customerBank;

  List<Map<String, dynamic>> _wallets = [];

  // Currency formatter
  final _currencyFormat = NumberFormat.currency(
      locale: 'vi_VN', 
      symbol: 'đ', 
      decimalDigits: 0,
    );

  @override
  void initState() {
    super.initState();
    _fetchWalletData();
  }

  // 🔗 REAL API: Fetch Wallet Data for Customer
  Future<void> _fetchWalletData() async {
    setState(() => _isLoading = true);
    
    try {
      // Customer chỉ cần gọi 1 API duy nhất lấy thông tin ví của mình
      final walletData = await _walletRepo.getMyWallet();
      final customerBank = await _walletRepo.getCustomerBanks();

      if (mounted && walletData != null) {
        setState(() {
          _availableBalance = walletData.balance;
          _pendingBalance = walletData.pendingBalance;

          // Mock Data Ngân hàng liên kết
          _wallets = [
            customerBank != null 
              ? {
                  'bank_name': customerBank.bankName,
                  'account_no': customerBank.accountNumber,
                  'is_default': true, // Giả sử chỉ có 1 tài khoản nên mặc định là default
                }
              : {
                  'bank_name': 'No linked bank',
                  'account_no': '',
                  'is_default': false,
                }
          ];
        });
      }
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(context, e.toString(), type: SnackBarType.error);
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  // 🔗 REAL API: Withdraw wallet
  void _onWithdrawTapped() {
    if (_availableBalance <= 0) {
      showAppSnackBar(context, "Insufficient balance to withdraw!", type: SnackBarType.error);
      return;
    }
    _showWithdrawDialog();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey[50],
      appBar: AppBar(
        title: const Text("My Wallet", style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: Colors.white,
        foregroundColor: _textBrown,
        elevation: 0,
        centerTitle: true,
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _fetchWalletData,
              color: _primaryRed,
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildMainBalanceCard(),
                    const SizedBox(height: 20),
                    _buildActionButtons(),
                    
                    // Chỉ hiển thị khối Pending nếu có số dư chờ (Ví dụ: Chờ hoàn tiền)
                    if (_pendingBalance > 0) ...[
                      const SizedBox(height: 24),
                      _buildPendingCard(),
                    ],
                    
                    const SizedBox(height: 30),
                    Text(
                      "Linked Accounts",
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: _textBrown),
                    ),
                    const SizedBox(height: 12),
                    _buildLinkedWallets(),
                  ],
                ),
              ),
            ),
    );
  }

  // --- UI: Khối A - Tổng quan số dư ---
  Widget _buildMainBalanceCard() {
    return Container(
      padding: const EdgeInsets.all(24),
      width: double.infinity,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [_primaryRed, _primaryRed.withOpacity(0.8)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(color: _primaryRed.withOpacity(0.3), blurRadius: 10, offset: const Offset(0, 5)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          const Text("Available Balance", style: TextStyle(color: Colors.white70, fontSize: 14)),
          const SizedBox(height: 8),
          Text(
            _currencyFormat.format(_availableBalance),
            style: const TextStyle(color: Colors.white, fontSize: 36, fontWeight: FontWeight.bold),
          ),
        ],
      ),
    );
  }

  // --- UI: Khối B - Trạng thái chờ ---
  Widget _buildPendingCard() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.blue[50],
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.blue[200]!),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              Icon(Icons.hourglass_empty, color: _infoBlue, size: 20),
              const SizedBox(width: 8),
              const Text("Pending Balance", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            ],
          ),
          Text(
            _currencyFormat.format(_pendingBalance),
            style: TextStyle(color: _infoBlue, fontWeight: FontWeight.bold, fontSize: 16),
          ),
        ],
      ),
    );
  }

  // --- UI: Action Buttons ---
  Widget _buildActionButtons() {
    return Row(
      children: [
        // Button Withdraw được làm cho bung rộng toàn màn hình
        Expanded(
          child: ElevatedButton.icon(
            onPressed: _onWithdrawTapped,
            icon: const Icon(Icons.account_balance_wallet),
            label: const Text("Withdraw to Bank", style: TextStyle(fontWeight: FontWeight.bold)),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.white,
              foregroundColor: _primaryRed,
              padding: const EdgeInsets.symmetric(vertical: 16),
              elevation: 2,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: ElevatedButton.icon(
            onPressed: () {
              // Gọi luồng xử lý liên tầng, truyền hàm _fetchWalletData làm callback để tự động reload lại trang ví khi liên kết thành công!
              _bankFlowHandler.startCreateBankFlow(context, _fetchWalletData);
            },
            icon: const Icon(Icons.add_card),
            label: const Text("Link Bank", style: TextStyle(fontWeight: FontWeight.bold)),
            style: ElevatedButton.styleFrom(
              backgroundColor: _textBrown,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 16),
              elevation: 2,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
      ],
    );
  }

  // --- UI: Linked Wallets List ---
  Widget _buildLinkedWallets() {
    if (_wallets.isEmpty) {
      return const Center(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Text("No linked accounts yet.", style: TextStyle(color: Colors.grey)),
        ),
      );
    }

    return ListView.separated(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: _wallets.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (context, index) {
        final wallet = _wallets[index];
        final isDefault = wallet['is_default'] ?? false;

        return Container(
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.grey[200]!),
          ),
          child: ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            leading: CircleAvatar(
              backgroundColor: Colors.grey[100],
              child: Icon(Icons.account_balance, color: _primaryRed),
            ),
            title: Text(wallet['bank_name'], style: TextStyle(fontWeight: FontWeight.bold, color: _textBrown)),
            subtitle: Text(wallet['account_no'], style: const TextStyle(letterSpacing: 1.2)),
            trailing: isDefault 
                ? Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(color: Colors.green[50], borderRadius: BorderRadius.circular(4)),
                    child: const Text("Default", style: TextStyle(color: Colors.green, fontSize: 12, fontWeight: FontWeight.bold)),
                  )
                : const Icon(Icons.more_vert, color: Colors.grey),
          ),
        );
      },
    );
  }

  // --- Dialogs ---
  void _showWithdrawDialog() {
    final TextEditingController amountController = TextEditingController();
    bool isSubmitting = false;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext dialogContext) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              title: const Text("Withdraw to Bank", style: TextStyle(fontWeight: FontWeight.bold)),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text("Wallet Balance: ${_currencyFormat.format(_availableBalance)}"),
                  const SizedBox(height: 16),
                  TextField(
                    controller: amountController,
                    keyboardType: const TextInputType.numberWithOptions(decimal: false),
                    decoration: InputDecoration(
                      labelText: "Withdrawal Amount",
                      hintText: "E.g. 100000",
                      prefixText: "₫ ",
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
                          final inputStr = amountController.text.trim();
                          if (inputStr.isEmpty) return;

                          final amount = double.tryParse(inputStr) ?? 0;
                          
                          // Validate số tiền tối thiểu (VD: 50k)
                          if (amount < 50000) {
                            showAppSnackBar(context, "Minimum withdrawal amount is 50,000 ₫", type: SnackBarType.warning);
                            return;
                          }
                          
                          if (amount > _availableBalance) {
                            showAppSnackBar(context, "You don't have enough balance.", type: SnackBarType.warning);
                            return;
                          }

                          setDialogState(() => isSubmitting = true);

                          try {
                            // 💡 GỌI API BƯỚC 1: Lấy Token & Gửi OTP
                            final requestResult = await _walletRepo.requestWithdrawal(amount);
                            
                            if (mounted) {
                              Navigator.pop(dialogContext); // Đóng form nhập tiền
                              showAppSnackBar(context, "OTP sent to your email!", type: SnackBarType.info);
                              
                              // 💡 CHUYỂN SANG BƯỚC 2: Mở form xác nhận OTP
                              _showOtpConfirmDialog(requestResult.resetSessionToken);
                            }
                          } catch (e) {
                            if (mounted) {
                              showAppSnackBar(context, e.toString(), type: SnackBarType.error);
                              setDialogState(() => isSubmitting = false);
                            }
                          }
                        },
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _primaryRed,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                  child: isSubmitting
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Text("Next", style: TextStyle(color: Colors.white)),
                ),
              ],
            );
          },
        );
      },
    ).whenComplete(amountController.dispose);
  }

  void _showOtpConfirmDialog(String sessionToken) {
    final TextEditingController otpController = TextEditingController();
    bool isVerifying = false;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext otpDialogContext) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              title: const Text("Security Verification", style: TextStyle(fontWeight: FontWeight.bold)),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    "To secure your funds, please enter the OTP we just sent to your registered email.", 
                    style: TextStyle(height: 1.4, color: Colors.black87)
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: otpController,
                    keyboardType: TextInputType.number,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 20, letterSpacing: 8, fontWeight: FontWeight.bold),
                    decoration: InputDecoration(
                      hintText: "000000",
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: isVerifying ? null : () => Navigator.pop(otpDialogContext),
                  child: const Text("Cancel", style: TextStyle(color: Colors.grey)),
                ),
                ElevatedButton(
                  onPressed: isVerifying
                      ? null
                      : () async {
                          final otp = otpController.text.trim();
                          if (otp.isEmpty) {
                            showAppSnackBar(context, "Please enter the OTP", type: SnackBarType.warning);
                            return;
                          }

                          setDialogState(() => isVerifying = true);

                          try {
                            // 💡 GỌI API BƯỚC 2: Xác thực OTP và Rút tiền
                            final result = await _walletRepo.confirmWithdrawal(sessionToken, otp);
                            
                            if (mounted) {
                              Navigator.pop(otpDialogContext); // Đóng form OTP
                              
                              if (result.success) {
                                // Lời nhắn thân thiện dành cho Customer
                                showAppSnackBar(context, "Withdrawal successful! The funds will arrive in your bank account shortly.", type: SnackBarType.success);
                                _fetchWalletData(); // Cập nhật lại UI số dư
                              } else {
                                showAppSnackBar(context, result.error.isNotEmpty ? result.error : "Verification failed", type: SnackBarType.error);
                              }
                            }
                          } catch (e) {
                            if (mounted) {
                              showAppSnackBar(context, e.toString(), type: SnackBarType.error);
                              setDialogState(() => isVerifying = false);
                            }
                          }
                        },
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _primaryRed,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                  child: isVerifying
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Text("Confirm & Withdraw", style: TextStyle(color: Colors.white)),
                ),
              ],
            );
          },
        );
      },
    ).whenComplete(otpController.dispose);
  }
}
