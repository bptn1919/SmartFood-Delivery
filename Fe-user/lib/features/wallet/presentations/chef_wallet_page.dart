import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:testing/features/common/app_components.dart';
import 'package:testing/features/wallet/models/chef_balance_model.dart';
import 'package:testing/features/wallet/models/chef_payment_model.dart';
import 'package:testing/features/wallet/models/wallet_model.dart';
import 'package:testing/features/wallet/presentations/widgets/chef_add_bank_page.dart';
import 'package:testing/features/wallet/repositories/wallet_repository.dart';

class ChefWalletPage extends StatefulWidget {
  final int chefId; // Thêm trường chefId để truyền vào API

  const ChefWalletPage({
    super.key, 
    required this.chefId
  });

  @override
  State<ChefWalletPage> createState() => _ChefWalletPageState();
}

class _ChefWalletPageState extends State<ChefWalletPage> {
  final WalletRepository _walletRepo = WalletRepository();
  
  // --- Styling ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _warningOrange = const Color(0xFFE67E22);
  final Color _infoBlue = const Color(0xFF3498DB);

  // --- State Variables ---
  bool _isLoading = true;
  double _availableBalance = 0;
  double _pendingBalance = 0;
  double _codDebt = 0;
  
  // New State Variables from Detailed Model
  double _totalSettled = 0;
  int _codUnsettledOrders = 0;
  String _codNote = '';
  String _payosNote = '';

  ChefPaymentModel? _paymentInfo;

  // Currency formatter (en_US locale uses commas for thousands: 2,500,000 ₫)
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

  // 🔗 REAL API: Fetch Wallet & Balance Data
  Future<void> _fetchWalletData() async {
    setState(() => _isLoading = true);
    
    try {
      // Gọi đồng thời 2 APIs bằng Future.wait để tối ưu tốc độ load
      final results = await Future.wait([
        _walletRepo.getMyWallet(), // Index 0: Fetch linked banks/wallets
        _walletRepo.getChefBalance(widget.chefId), // Index 1: Fetch balances
        _walletRepo.getChefPaymentInfo(), // Index 2: Fetch chef payment info
      ]);

      final walletData = results[0] as WalletModel?;
      final balanceData = results[1] as ChefBalanceModel?;
      final paymentData = results[2] as ChefPaymentModel?;

      if (mounted) {
        setState(() {
          // Cập nhật số dư từ API Get Chef Balance (ưu tiên nguồn chính xác nhất với nghiệp vụ)
          if (balanceData != null) {
            _availableBalance = balanceData.totalAvailablePayout;
            _totalSettled = balanceData.totalSettled;
            
            // Dữ liệu COD
            _codDebt = balanceData.codBalance.unsettledBalance;
            _codUnsettledOrders = balanceData.codBalance.unsettledOrders;
            _codNote = balanceData.codBalance.note;
            
            // Dữ liệu PayOS (Pending)
            _pendingBalance = balanceData.payosBalance.pendingPayout;
            _payosNote = balanceData.payosBalance.note;
          } else if (walletData != null) {
            // Fallback nếu lỗi API Chef Balance
            _availableBalance = walletData.balance;
            _pendingBalance = walletData.pendingBalance;
          }

          _paymentInfo = paymentData;
        });
      }
    } catch (e) {
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

  // 🔗 REAL API: Settle COD Debt
  void _onSettleCodTapped() {
    if (_codDebt <= 0) {
      showAppSnackBar(context, "You have no COD debt to settle.", type: SnackBarType.info);
      return;
    }

    if (_availableBalance < _codDebt) {
      showAppSnackBar(context, "Insufficient wallet balance to cover COD debt. Please contact support.", type: SnackBarType.error);
      return;
    }

    _showSettleConfirmationDialog();
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
                    const SizedBox(height: 24),
                    _buildBreakdownCards(), // Khu vực hiển thị COD & Pending mới
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

  // --- UI: Khối A - Tổng quát & Thành tựu ---
  Widget _buildMainBalanceCard() {
    return Container(
      padding: const EdgeInsets.all(24),
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
          const Text("Available Payout", style: TextStyle(color: Colors.white70, fontSize: 14)),
          const SizedBox(height: 8),
          Text(
            _currencyFormat.format(_availableBalance),
            style: const TextStyle(color: Colors.white, fontSize: 36, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 20),
          Container(height: 1, color: Colors.white24),
          const SizedBox(height: 16),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.workspace_premium, color: Colors.amber, size: 20),
              const SizedBox(width: 8),
              Text(
                "Lifetime Earnings: ${_currencyFormat.format(_totalSettled)}",
                style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w500),
              ),
            ],
          )
        ],
      ),
    );
  }

  // --- UI: Khối B & C - Chi tiết dòng tiền ---
  Widget _buildBreakdownCards() {
    return Column(
      children: [
        // COD Debt Card
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.orange[50],
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.orange[200]!),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Icon(Icons.money_off, color: _warningOrange, size: 20),
                      const SizedBox(width: 8),
                      const Text("COD Collected", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                    ],
                  ),
                  Text(
                    _codDebt > 0 ? "- ${_currencyFormat.format(_codDebt)}" : "0 ₫",
                    style: TextStyle(color: _warningOrange, fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                ],
              ),
              if (_codDebt > 0) ...[
                const SizedBox(height: 8),
                Text("From $_codUnsettledOrders unsettled order(s).", style: TextStyle(color: Colors.grey[700], fontSize: 13)),
                if (_codNote.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.info_outline, size: 14, color: _warningOrange),
                      const SizedBox(width: 4),
                      Expanded(child: Text(_codNote, style: TextStyle(color: _warningOrange, fontSize: 12, fontStyle: FontStyle.italic))),
                    ],
                  )
                ]
              ]
            ],
          ),
        ),
        const SizedBox(height: 12),
        // Pending Payout Card
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.blue[50],
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.blue[200]!),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Icon(Icons.hourglass_empty, color: _infoBlue, size: 20),
                      const SizedBox(width: 8),
                      const Text("Pending Payout", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                    ],
                  ),
                  Text(
                    _currencyFormat.format(_pendingBalance),
                    style: TextStyle(color: _infoBlue, fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                ],
              ),
              if (_pendingBalance > 0 && _payosNote.isNotEmpty) ...[
                const SizedBox(height: 8),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.info_outline, size: 14, color: _infoBlue),
                    const SizedBox(width: 4),
                    Expanded(child: Text(_payosNote, style: TextStyle(color: _infoBlue, fontSize: 12, fontStyle: FontStyle.italic))),
                  ],
                )
              ]
            ],
          ),
        ),
      ],
    );
  }

  // --- UI: Action Buttons ---
  Widget _buildActionButtons() {
    return Row(
      children: [
        Expanded(
          child: ElevatedButton.icon(
            onPressed: _onWithdrawTapped,
            icon: const Icon(Icons.account_balance_wallet),
            label: const Text("Withdraw", style: TextStyle(fontWeight: FontWeight.bold)),
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
            onPressed: _onSettleCodTapped,
            icon: const Icon(Icons.receipt_long),
            label: const Text("Settle COD", style: TextStyle(fontWeight: FontWeight.bold)),
            style: ElevatedButton.styleFrom(
              backgroundColor: _codDebt > 0 ? _textBrown : Colors.grey[300],
              foregroundColor: _codDebt > 0 ? Colors.white : Colors.grey[600],
              padding: const EdgeInsets.symmetric(vertical: 16),
              elevation: _codDebt > 0 ? 2 : 0,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
      ],
    );
  }

  // --- UI: Linked Wallets List ---
  Widget _buildLinkedWallets() {
    if (_paymentInfo == null) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.grey[200]!),
        ),
        child: Column(
          children: [
            Icon(Icons.account_balance, size: 40, color: Colors.grey[300]),
            const SizedBox(height: 12),
            const Text("No linked bank account yet.", style: TextStyle(color: Colors.grey)),
            const SizedBox(height: 8),
            TextButton.icon(
              onPressed: () async {
                // TODO: Chuyển hướng sang trang thêm tài khoản ngân hàng (gọi API POST)
                final result = await Navigator.push(
                  context,
                  MaterialPageRoute(builder: (context) => const ChefAddBankPage()),
                );

                // Nếu trang Add Bank trả về true (Tạo thành công), gọi lại API load ví
                if (result == true && mounted) {
                  _fetchWalletData();
                }
              }, 
              icon: Icon(Icons.add, color: _primaryRed), 
              label: Text("Add Account", style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold))
            )
          ],
        ),
      );
    }

    // 💡 Render thẻ ngân hàng lấy từ API
    final bool isVerified = _paymentInfo!.isVerified;

    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: isVerified ? Colors.green[200]! : Colors.grey[200]!),
      ),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: CircleAvatar(
          backgroundColor: Colors.grey[100],
          child: Icon(Icons.account_balance, color: _primaryRed),
        ),
        title: Text(_paymentInfo!.bankName, style: TextStyle(fontWeight: FontWeight.bold, color: _textBrown)),
        
        // Ẩn bớt số tài khoản cho bảo mật (VD: **** **** 1234)
        subtitle: Text(
          _paymentInfo!.bankAccountNumber.length > 4 
              ? "**** **** ${_paymentInfo!.bankAccountNumber.substring(_paymentInfo!.bankAccountNumber.length - 4)}"
              : _paymentInfo!.bankAccountNumber, 
          style: const TextStyle(letterSpacing: 1.2)
        ),
        
        // Hiện trạng thái Verified/Pending
        trailing: isVerified 
            ? Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(color: Colors.green[50], borderRadius: BorderRadius.circular(4)),
                child: const Text("Verified", style: TextStyle(color: Colors.green, fontSize: 12, fontWeight: FontWeight.bold)),
              )
            : Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(color: Colors.orange[50], borderRadius: BorderRadius.circular(4)),
                child: const Text("Pending", style: TextStyle(color: Colors.orange, fontSize: 12, fontWeight: FontWeight.bold)),
              ),
      ),
    );
  }

  // --- Dialogs ---
  // --- UI: Bước 1 - Dialog Nhập Số Tiền ---
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
              title: const Text("Withdraw Funds", style: TextStyle(fontWeight: FontWeight.bold)),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text("Available: ${_currencyFormat.format(_availableBalance)}"),
                  const SizedBox(height: 16),
                  TextField(
                    controller: amountController,
                    keyboardType: const TextInputType.numberWithOptions(decimal: false),
                    decoration: InputDecoration(
                      labelText: "Amount to withdraw",
                      hintText: "E.g. 500000",
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
                          if (amount <= 0) {
                            showAppSnackBar(context, "Please enter a valid amount", type: SnackBarType.warning);
                            return;
                          }
                          if (amount > _availableBalance) {
                            showAppSnackBar(context, "Amount exceeds available balance", type: SnackBarType.warning);
                            return;
                          }

                          setDialogState(() => isSubmitting = true);

                          try {
                            // 💡 TECH LEAD FIX: Gọi API Bước 1
                            final requestResult = await _walletRepo.requestWithdrawal(amount);
                            
                            if (mounted) {
                              Navigator.pop(dialogContext); // Đóng form nhập tiền
                              showAppSnackBar(context, "OTP sent to your email!", type: SnackBarType.info);
                              
                              // 💡 Mở tiếp Bước 2: Truyền token sang Form nhập OTP
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

  // --- UI: Bước 2 - Dialog Nhập OTP Xác Nhận ---
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
                  const Text("Please enter the OTP sent to your registered email to confirm this withdrawal.", style: TextStyle(height: 1.4, color: Colors.black87)),
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
                            // 💡 TECH LEAD FIX: Gọi API Bước 2
                            final result = await _walletRepo.confirmWithdrawal(sessionToken, otp);
                            
                            if (mounted) {
                              Navigator.pop(otpDialogContext); // Đóng Form OTP
                              
                              if (result.success) {
                                showAppSnackBar(context, "Withdrawal successful! Processing your payout.", type: SnackBarType.success);
                                _fetchWalletData(); // Load lại số dư ví mới nhất
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

  void _showSettleConfirmationDialog() {
    bool isSubmitting = false;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext dialogContext) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              title: const Text("Settle COD Debt", style: TextStyle(fontWeight: FontWeight.bold)),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text("Do you want to settle your COD debt using your available wallet balance?", style: TextStyle(height: 1.4)),
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(color: Colors.yellow[50], borderRadius: BorderRadius.circular(8)),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text("Debt Amount:", style: TextStyle(fontWeight: FontWeight.w600, color: Colors.orange)),
                        Text(_currencyFormat.format(_codDebt), style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.red)),
                      ],
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
                          setDialogState(() => isSubmitting = true);

                          try {
                            final currentChefId = widget.chefId;
                            final result = await _walletRepo.settleCod(currentChefId);
                            
                            if (mounted) Navigator.pop(dialogContext);

                            if (result.success) {
                              showAppSnackBar(context, "COD Debt settled successfully!", type: SnackBarType.success);
                              _fetchWalletData();
                            } else {
                              showAppSnackBar(context, result.error.isNotEmpty ? result.error : "Settlement failed", type: SnackBarType.error);
                            }
                          } catch (e) {
                            if (mounted) Navigator.pop(dialogContext);
                            showAppSnackBar(context, e.toString(), type: SnackBarType.error);
                          }
                        },
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _textBrown,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                  child: isSubmitting
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Text("Confirm & Pay", style: TextStyle(color: Colors.white)),
                ),
              ],
            );
          },
        );
      },
    );
  }
}
