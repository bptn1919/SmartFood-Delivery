import 'package:flutter/material.dart';
import '../models/order_draft.dart';
import '../repositories/edit_order_repository.dart';
import '../../common/app_components.dart';
import '../models/payment_method_enum.dart';

class PaymentMethodOption {
  PaymentMethodOption({
    required this.title,
    required this.subtitle,
    required this.icon,
    this.tag,
  });

  final String title;
  final String subtitle;
  final IconData icon;
  final String? tag;
}

class EditPaymentPage extends StatefulWidget {
  const EditPaymentPage({
    super.key,
    this.initialTag,
    required this.draft,
  });

  final String? initialTag;
  final OrderDraft draft;

  @override
  State<EditPaymentPage> createState() => _EditPaymentPageState();
}

class _EditPaymentPageState extends State<EditPaymentPage> {
  // Bảng màu bám sát thiết kế Figma giống trang Address
  final Color _kPeachBg = const Color(0xFFFFC6A6);
  final Color _kPrimaryPink = const Color(0xFFD85C6B);
  final Color _kDarkBrown = const Color(0xFF4A2C2A);
  final Color _kLightPinkLine = const Color(0xFFF3D5D8);

  final EditOrderRepository _repository = EditOrderRepository();
  bool _isLoading = false;

  late List<PaymentMethodOption> _methods;
  int _selected = 0;

  @override
  void initState() {
    super.initState();

    debugPrint('Initial Payment Method in Draft: ${widget.draft.uid}');

    final phone = widget.draft.phoneNumber;
    final transferSub = phone.isNotEmpty
        ? 'Online Transfer'
        : 'Online Transfer';

    _methods = [
      PaymentMethodOption(
        title: "Bank Transfer", // Hoặc "Chuyển khoản Online"
        subtitle: transferSub,
        
        // 💡 TECH LEAD FIX: Đổi icon sang hình mã QR sẽ trực quan hơn rất nhiều 
        // cho phương thức quét mã VietQR qua PayOS
        icon: Icons.qr_code_scanner, 
        
        // 💡 TECH LEAD FIX: Đổi tag thành 'payos' để đồng bộ với logic kiểm tra 
        // ở hàm _placeOrder (current.paymentMethod.toUpperCase() == 'PAYOS')
        tag: "payos", 
      ),
      PaymentMethodOption(
        title: "Cash on Delivery",
        subtitle: "Pay when you receive",
        icon: Icons.local_shipping_outlined,
        tag: "cod",
      ),
    ];

    if (widget.initialTag != null) {
      final idx = _methods.indexWhere((m) => m.tag == widget.initialTag);
      if (idx != -1) _selected = idx;
    } else {
      final pm = widget.draft.paymentMethod.toUpperCase();
      if (pm == 'MOMO') {
        _selected = 0;
      } else if (pm == 'COD') {
        _selected = 1;
      }
    }
  }

  Future<void> _confirm() async {
    setState(() => _isLoading = true);

    try {
      final orderUid = widget.draft.uid;

      final selectedEnum = (_selected == 0)
          ? PaymentMethodEnum.PAYOS
          : PaymentMethodEnum.COD;

      debugPrint('Selected Payment Method Enum: $orderUid');

      final updatedOrder = await _repository.editPaymentMethodOfOrder(
        uid: orderUid,
        paymentMethod: selectedEnum,
      );

      if (!mounted) return;

      showAppSnackBar(
        context,
        'Payment method updated successfully!',
        type: SnackBarType.success, // Tự động có icon check, màu xanh, bo góc và nổi lên
      );

      final isMomo = updatedOrder.paymentMethod.toUpperCase() == 'MOMO';

      Navigator.pop<Map<String, dynamic>>(context, {
        'tag': isMomo ? 'momo' : 'cod',
        'title': isMomo ? 'MoMo' : 'Cash on Delivery',
        'subtitle': isMomo
            ? (widget.draft.phoneNumber.isNotEmpty
                ? 'Pay with MoMo • ${widget.draft.phoneNumber}'
                : 'Pay with MoMo')
            : 'Pay when you receive',
        'updated_draft': updatedOrder,
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      showAppSnackBar(
        context,
        'Failed to update payment method: $e', 
        type: SnackBarType.error, 
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final disabled = _isLoading;

    return Scaffold(
      backgroundColor: _kPeachBg,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            // ===== HEADER =====
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
              child: Row(
                children: [
                  IconButton(
                    icon: Icon(Icons.arrow_back_ios_new, color: _kPrimaryPink, size: 20),
                    onPressed: disabled ? null : () => Navigator.of(context).pop(),
                  ),
                  Expanded(
                    child: Text(
                      "Payment Method",
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: _kDarkBrown,
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(width: 48), // Spacer để title ở chính giữa
                ],
              ),
            ),

            // ===== BODY =====
            Expanded(
              child: AbsorbPointer(
                absorbing: disabled,
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
                      // Payment Methods List
                      Expanded(
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
                          child: ListView.separated(
                            shrinkWrap: true,
                            physics: const NeverScrollableScrollPhysics(),
                            itemCount: _methods.length,
                            separatorBuilder: (_, __) => Divider(
                              height: 32,
                              thickness: 1,
                              color: _kLightPinkLine,
                            ),
                            itemBuilder: (_, i) => _methodTile(_methods[i], i),
                          ),
                        ),
                      ),
                      
                      // ===== Bottom Action Button (Giống trang Address) =====
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
                            onPressed: disabled ? null : _confirm,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: _kPrimaryPink,
                              disabledBackgroundColor: Colors.grey.shade300,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(25),
                              ),
                              elevation: 0,
                            ),
                            child: disabled
                                ? const SizedBox(
                                    width: 20,
                                    height: 20,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: Colors.white,
                                    ),
                                  )
                                : const Text(
                                    "Confirm Selection",
                                    style: TextStyle(
                                      color: Colors.white,
                                      fontSize: 16,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                          ),
                        ),
                      ),
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

  Widget _methodTile(PaymentMethodOption m, int index) {
    final selected = index == _selected;

    return GestureDetector(
      onTap: () => setState(() => _selected = index),
      behavior: HitTestBehavior.opaque,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          // Icon
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              color: _kLightPinkLine.withOpacity(0.5),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(m.icon, color: _kPrimaryPink, size: 24),
          ),
          const SizedBox(width: 16),
          
          // Title & Subtitle
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  m.title,
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 15,
                    color: _kDarkBrown,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  m.subtitle,
                  style: TextStyle(
                    color: _kDarkBrown.withOpacity(0.6),
                    fontSize: 13,
                  ),
                ),
              ],
            ),
          ),
          
          const SizedBox(width: 16),
          
          // Custom Radio Button (Chuẩn Figma)
          Container(
            width: 20,
            height: 20,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: _kPrimaryPink, width: 1.5),
            ),
            child: selected
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
  }
}