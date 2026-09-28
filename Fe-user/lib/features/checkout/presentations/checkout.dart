import 'package:flutter/material.dart';
import 'package:testing/features/checkout/models/payos_payment_model.dart';
import 'package:testing/features/checkout/models/place_order_response_model.dart';
import 'package:testing/features/checkout/presentations/edit_address.dart';
import 'package:testing/features/checkout/presentations/edit_payment.dart';
import 'package:testing/features/checkout/presentations/edit_personal.dart';
import 'package:testing/features/checkout/presentations/widgets/chef_order_groups_section.dart';
import 'package:testing/features/checkout/presentations/widgets/checkout_address_section.dart';
import 'package:testing/features/checkout/presentations/widgets/edit_delivery_time_sheet.dart';
import 'package:testing/features/checkout/presentations/widgets/checkout_payment_section.dart';
import 'package:testing/features/checkout/presentations/widgets/personal_info_section.dart';
import 'package:testing/features/checkout/presentations/widgets/platform_vouchers_section.dart';
import 'package:testing/features/checkout/presentations/widgets/price_summary_section.dart';
import 'package:testing/features/checkout/repositories/edit_order_repository.dart';
import '../../common/app_components.dart';
import 'package:testing/features/checkout/repositories/order_repository.dart';
import 'package:testing/features/home/repositories/cart_repository.dart';
import 'package:intl/intl.dart';
import '../models/order_draft.dart';
import 'dart:math' as math;
import '../common/payos_qr_dialog.dart';
import '../../chef_manager/models/voucher_model.dart';

// Màu sắc hằng số bám sát Figma
const Color _kPrimaryPink = Color(0xFFD85C6B);
const Color _kDarkBrown = Color(0xFF4A2C2A);
const Color _kGreyText = Color(0xFF8B8B8B);
const Color _kLightPinkLine = Color(0xFFF3D5D8);
const Color _kPeachBg = Color(0xFFFFC6A6);
const Color _kBgWhite = Color(0xFFFBFBFB);
const String _kDefaultDeliveryType = 'SELF_PICKUP';

class CheckoutPage extends StatefulWidget {
  final OrderDraft draft;
  const CheckoutPage({super.key, required this.draft});

  @override
  State<CheckoutPage> createState() => _CheckoutPageState();
}

class _CheckoutPageState extends State<CheckoutPage> {
  final CartRepository _cartRepo = CartRepository();
  final OrderRepository _orderRepo = OrderRepository();
  final EditOrderRepository _editOrderRepo = EditOrderRepository();

  OrderDraft? _order;
  bool _placing = false;

  String _fullName = '';
  String _phone = '';

  String _paymentTitle = '';
  String _paymentSub = '';
  String _paymentTag = ''; // 'momo' | 'cod' | 'payos'

  final Map<int, String> _deliveryTypes = {};
  final Map<int, DateTime> _selectedDeliveryDates = {};
  final Map<int, String> _selectedDeliveryTimes = {};
  bool _isRecalculating = false;

  // 1. Quản lý Shop Voucher
  final Map<int, String> _appliedShopVouchers = {};
  final Map<int, double> _shopDiscounts = {};
  final Map<int, TextEditingController> _shopVoucherCtrls = {};
  int? _validatingChefId;

  // 2. Quản lý Platform Voucher
  String? _appliedPlatformSubtotal;

  // 3. Quản lý Freeship Voucher
  String? _appliedPlatformShipping;

  final TextEditingController _platformSubtotalCtrl = TextEditingController();
  final TextEditingController _platformShippingCtrl = TextEditingController();
  double _platformSubtotalDiscount = 0.0;
  double _platformShippingDiscount = 0.0;
  String? _validatingPlatformType;

  @override
  void dispose() {
    _platformSubtotalCtrl.dispose();
    _platformShippingCtrl.dispose();
    for (var ctrl in _shopVoucherCtrls.values) {
      ctrl.dispose();
    }
    debugPrint(
        "CheckoutPage disposed and all controllers cleaned up. ${widget.draft.uid}");
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _order = widget.draft;
    _bindFromOrder(widget.draft);
    _initializeDefaultDeliveryTypes(widget.draft);
  }

  OrderDraft get _currentOrder => _order ?? widget.draft;

  String get _effectivePaymentMethod {
    if (_paymentTag.isNotEmpty) return _paymentTag.toUpperCase();
    return _currentOrder.paymentMethod.toUpperCase();
  }

  TextEditingController _voucherControllerForChef(int chefId) {
    return _shopVoucherCtrls.putIfAbsent(
      chefId,
      () => TextEditingController(),
    );
  }

  String? _orderUidForChef(int chefId) {
    for (final line in _currentOrder.lines) {
      if (line.chefId == chefId && line.orderUid.isNotEmpty) {
        return line.orderUid;
      }
    }
    return null;
  }

  double _readMoney(Map<String, dynamic> json, String key) {
    final value = json[key];
    if (value is num) return value.toDouble();
    if (value is String) return double.tryParse(value) ?? 0.0;
    return 0.0;
  }

  // === Date/time helpers ===
  DateTime? _parseYmd(String? s) {
    if (s == null || s.isEmpty) return null;
    try {
      final p = s.split('-');
      return DateTime(int.parse(p[0]), int.parse(p[1]), int.parse(p[2]));
    } catch (_) {
      return null;
    }
  }

  String _two(int value) => value.toString().padLeft(2, '0');

  String _formatYmd(DateTime date) {
    return '${date.year}-${_two(date.month)}-${_two(date.day)}';
  }

  String _formatHms(String time) {
    final trimmed = time.trim();
    if (trimmed.isEmpty) return '00:00:00';

    final parts = trimmed.split(':');
    final hour = int.tryParse(parts.isNotEmpty ? parts[0] : '') ?? 0;
    final minute = int.tryParse(parts.length > 1 ? parts[1] : '') ?? 0;
    final second = int.tryParse(parts.length > 2 ? parts[2] : '') ?? 0;

    return '${_two(hour)}:${_two(minute)}:${_two(second)}';
  }

  List<Map<String, dynamic>> _buildSubOrderSchedules() {
    final chefIds = _currentOrder.lines.map((line) => line.chefId).toSet();
    final fallbackDate =
        _parseYmd(_currentOrder.deliveryDate) ?? DateTime.now();
    final fallbackTime = _currentOrder.deliveryTime;
    final dateFormatter = DateFormat('yyyy-MM-dd');

    return chefIds.map((chefId) {
      final selectedDate = _selectedDeliveryDates[chefId] ?? fallbackDate;
      final selectedTime = _selectedDeliveryTimes[chefId] ?? fallbackTime;

      return {
        'chef_id': chefId,
        'delivery_date': dateFormatter.format(selectedDate),
        'delivery_time': _formatHms(selectedTime),
      };
    }).toList();
  }

  void _bindFromOrder(OrderDraft o) {
    _fullName = o.fullName;
    _phone = o.phoneNumber;

    // payment
    final pm = o.paymentMethod.toUpperCase();
    if (pm == 'COD') {
      _paymentTag = 'cod';
      _paymentTitle = 'Cash on Delivery';
      _paymentSub = '';
    } else if (pm == 'PAYOS') {
      _paymentTag = 'payos';
      _paymentTitle = 'PayOs';
      _paymentSub = _phone.isNotEmpty ? _phone : '';
    } else if (pm.isNotEmpty) {
      _paymentTag = pm.toLowerCase();
      _paymentTitle = pm;
      _paymentSub = '';
    } else {
      _paymentTag = '';
      _paymentTitle = '';
      _paymentSub = '';
    }
  }

  void _initializeDefaultDeliveryTypes(OrderDraft draft) {
    final chefIds = draft.lines.map((line) => line.chefId).toSet();
    for (final chefId in chefIds) {
      _deliveryTypes.putIfAbsent(chefId, () => _kDefaultDeliveryType);
    }
  }

  Future<void> _openEditPersonal() async {
    final res = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(
        builder: (_) => EditPersonalPage(
          initialName: _fullName,
          initialPhone: _phone,
          draft: _currentOrder,
        ),
      ),
    );
    if (!mounted || res == null) return;

    final updated = res['updated_draft'] as OrderDraft?;
    if (updated != null) {
      setState(() {
        _order = updated;
        _bindFromOrder(updated);
      });
      return;
    }

    setState(() {
      _fullName = res['name'] ?? _fullName;
      _phone = res['phone'] ?? _phone;
    });
  }

  Future<void> _openEditAddress() async {
    final current = _currentOrder;

    final res = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(
        builder: (_) =>
            EditAddressPage(orderUid: current.uid, initialAddressId: null),
      ),
    );

    if (!mounted || res == null) return;

    final updated = res['updated_draft'] as OrderDraft?;
    if (updated != null) {
      setState(() {
        _order = updated;
        _bindFromOrder(updated);
      });
    }
  }

  Future<void> _openEditPayment() async {
    debugPrint(
        'Opening EditPaymentPage with initial payment method: ${widget.draft.uid}');
    final res = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(
        builder: (_) => EditPaymentPage(
          initialTag: _paymentTag,
          draft: _currentOrder,
        ),
      ),
    );
    if (!mounted || res == null) return;

    final updated = res['updated_draft'] as OrderDraft?;
    if (updated != null) {
      setState(() {
        _order = updated;
        _bindFromOrder(updated);
      });
      return;
    }

    setState(() {
      _paymentTag = res['tag'] ?? _paymentTag;
      _paymentTitle = res['title'] ?? _paymentTitle;
      _paymentSub = res['subtitle'] ?? _paymentSub;
    });
  }

  Future<void> _openEditTimeForChef(int chefId) async {
    final current = _currentOrder;
    final selectedDate = _selectedDeliveryDates[chefId];
    final selectedTime = _selectedDeliveryTimes[chefId];
    final initialDate =
        selectedDate == null ? current.deliveryDate : _formatYmd(selectedDate);
    final initialTime = selectedTime ?? current.deliveryTime;

    final result = await showModalBottomSheet<EditDeliveryTimeResult>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => EditDeliveryTimeSheet(
        initialDeliveryDate: initialDate,
        initialDeliveryTime: initialTime,
      ),
    );

    if (!mounted || result == null) return;

    setState(() {
      _selectedDeliveryDates[chefId] =
          _parseYmd(result.deliveryDate) ?? DateTime.now();
      _selectedDeliveryTimes[chefId] = result.deliveryTime;
    });
  }

  Future<void> _changeDeliveryType(int chefId, String newType) async {
    // 1. Bỏ qua nếu user bấm trùng lựa chọn cũ
    if (_deliveryTypes[chefId] == newType) return;

    // 2. Bật loading vòng xoay và lưu tạm lựa chọn mới
    setState(() {
      _deliveryTypes[chefId] = newType;
      _isRecalculating = true;
    });

    try {
      // 3. Bắn thẳng chefId xuống Repo (KHÔNG CẦN tìm orderUid nữa)
      final updatedDraft = await _editOrderRepo.updateDeliveryType(
        checkoutUid: _currentOrder.uid,
        chefId: chefId, // 👈 Truyền trực tiếp chefId vào đây
        deliveryType: newType,
      );

      // 4. Nếu thành công, lấy Bill mới đắp lên UI
      if (updatedDraft != null && mounted) {
        setState(() {
          _order = updatedDraft;
        });
      }
    } catch (e) {
      if (mounted) {
        showAppSnackBar(context, 'Failed to update delivery fee: $e',
            type: SnackBarType.error);
        // Rollback lại UI (xóa lựa chọn) nếu API báo lỗi
        setState(() {
          _deliveryTypes.remove(chefId);
        });
      }
    } finally {
      if (mounted) {
        // Tắt loading
        setState(() => _isRecalculating = false);
      }
    }
  }

  double _getTotalDiscount() {
    double total = 0.0;
    _shopDiscounts.forEach((_, discount) => total += discount);
    total += _platformSubtotalDiscount;
    total += _platformShippingDiscount;
    return total;
  }

  Future<void> _applyShopVoucher(int chefId, String code) async {
    final cleanCode = code.trim();
    if (cleanCode.isEmpty) {
      showAppSnackBar(
        context,
        'Please enter a voucher code!',
        type:
            SnackBarType.warning, // Cảnh báo nhẹ nhàng để user tự giảm số lượng
      );
      return;
    }

    setState(() => _validatingChefId = chefId);

    try {
      final orderUid = _orderUidForChef(chefId);
      if (orderUid == null || orderUid.isEmpty) {
        throw Exception('Missing order UID for this chef group.');
      }

      final updatedOrder =
          await _orderRepo.applyShopVoucher(orderUid, cleanCode);

      if (updatedOrder != null) {
        setState(() {
          _appliedShopVouchers[chefId] = cleanCode;
          _shopDiscounts[chefId] = _readMoney(updatedOrder, 'shop_discount');
          _validatingChefId = null;
        });
        if (mounted) {
          showAppSnackBar(
            context,
            'Shop voucher applied successfully!',
            type: SnackBarType.success,
          );
        }
      } else {
        setState(() => _validatingChefId = null);
        if (mounted) {
          showAppSnackBar(
            context,
            "Invalid voucher",
            type: SnackBarType.warning,
          );
        }
      }
    } catch (e) {
      setState(() => _validatingChefId = null);
      if (mounted) {
        showAppSnackBar(
          context,
          'System error: $e',
          type: SnackBarType.error,
        );
      }
    }
  }

  void _removeShopVoucher(int chefId) {
    setState(() {
      _appliedShopVouchers.remove(chefId);
      _shopDiscounts.remove(chefId);
      _shopVoucherCtrls[chefId]?.clear();
    });
  }

  Future<void> _applyPlatformVoucher(String type, String code) async {
    final cleanCode = code.trim();
    if (cleanCode.isEmpty) {
      showAppSnackBar(
        context,
        'Please enter a voucher code!',
        type: SnackBarType.warning, // Cảnh báo (Cam) do quên nhập liệu
      );
      return;
    }

    setState(() => _validatingPlatformType = type);

    try {
      final updatedCheckout = await _orderRepo.applyPlatformVoucher(
        _currentOrder.uid,
        cleanCode,
        type,
      );

      if (updatedCheckout == null) {
        throw Exception('Invalid platform voucher');
      }

      final updatedDraft = OrderDraft.fromJson(updatedCheckout);

      setState(() {
        _order = updatedDraft;
        if (type == "PLATFORM_SUBTOTAL") {
          _appliedPlatformSubtotal = cleanCode;
          _platformSubtotalDiscount =
              _readMoney(updatedCheckout, 'platform_subtotal_discount');
        } else if (type == "PLATFORM_SHIPPING") {
          _appliedPlatformShipping = cleanCode;
          _platformShippingDiscount =
              _readMoney(updatedCheckout, 'platform_shipping_discount');
        }
        _validatingPlatformType = null;
      });

      if (mounted) {
        showAppSnackBar(
          context,
          'System code recorded successfully!',
          type: SnackBarType.success,
        );
      }
    } catch (e) {
      setState(() => _validatingPlatformType = null);
      if (mounted) {
        showAppSnackBar(
          context,
          'Error: $e',
          type: SnackBarType.error, // Màu xanh lá chuẩn của app
        );
      }
    }
  }

  void _removePlatformVoucher(String type) {
    setState(() {
      if (type == "PLATFORM_SUBTOTAL") {
        _appliedPlatformSubtotal = null;
        _platformSubtotalDiscount = 0.0;
        _platformSubtotalCtrl.clear();
      } else if (type == "PLATFORM_SHIPPING") {
        _appliedPlatformShipping = null;
        _platformShippingDiscount = 0.0;
        _platformShippingCtrl.clear();
      }
    });
  }

  void _showVoucherList(BuildContext context, int chefId) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (context) {
        return Container(
          padding: const EdgeInsets.all(16),
          height: MediaQuery.of(context).size.height * 0.5,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text("Available Vouchers",
                  style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: _kDarkBrown)),
              const SizedBox(height: 10),
              Expanded(
                child: FutureBuilder<List<VoucherModel>>(
                  future: _orderRepo.getChefVouchers(chefId),
                  builder: (context, snapshot) {
                    if (snapshot.connectionState == ConnectionState.waiting) {
                      return const Center(
                          child:
                              CircularProgressIndicator(color: _kPrimaryPink));
                    }
                    if (!snapshot.hasData || snapshot.data!.isEmpty) {
                      return const Center(
                          child: Text("No vouchers available for this chef.",
                              style: TextStyle(color: _kGreyText)));
                    }

                    final vouchers = snapshot.data!;
                    return ListView.builder(
                      itemCount: vouchers.length,
                      itemBuilder: (context, index) {
                        final v = vouchers[index];
                        return Card(
                          margin: const EdgeInsets.only(bottom: 10),
                          color: const Color(0xFFFFF9FA),
                          elevation: 0,
                          shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(10),
                              side: const BorderSide(color: _kLightPinkLine)),
                          child: ListTile(
                            leading: const Icon(Icons.local_offer,
                                color: _kPrimaryPink),
                            title: Text(v.code,
                                style: const TextStyle(
                                    fontWeight: FontWeight.bold,
                                    color: _kDarkBrown)),
                            subtitle: Text(
                                "${v.description}\nMin order: \$${v.minOrderAmount}",
                                style: const TextStyle(color: _kGreyText)),
                            isThreeLine: true,
                            trailing: TextButton(
                              onPressed: () {
                                _voucherControllerForChef(chefId).text = v.code;
                                Navigator.pop(context);
                                _applyShopVoucher(chefId, v.code);
                              },
                              style: TextButton.styleFrom(
                                  backgroundColor: _kPrimaryPink,
                                  foregroundColor: Colors.white),
                              child: const Text("Use"),
                            ),
                          ),
                        );
                      },
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  double get _taxAndFees => _currentOrder.taxAndFees;
  double get _deliveryFee => _currentOrder.deliveryFee;

  bool get _isMomoPayment => _effectivePaymentMethod == 'PAYOS';

  Future<void> _placeOrder() async {
    final current = _currentOrder;
    if (_placing || current.uid.isEmpty) return;

    final draftUid = current.uid;

    if (_effectivePaymentMethod.isEmpty) {
      showAppSnackBar(context, 'Please choose a payment method.',
          type: SnackBarType.warning);
      return;
    }

    setState(() => _placing = true);

    try {
      final checkoutData = await _createOrder(draftUid);

      if (!mounted) return;

      if (checkoutData == null || checkoutData.uid.isEmpty) {
        showAppSnackBar(context, 'Failed to create order. Please try again.',
            type: SnackBarType.error);
        setState(() => _placing = false);
        return;
      }

      setState(() => _placing = false);

      if (_isMomoPayment) {
        _handlePayOSPayment(checkoutData);
        return;
      }

      _handleCodSuccess();
    } catch (e) {
      if (!mounted) return;
      showAppSnackBar(context, 'System error: $e', type: SnackBarType.error);
      setState(() => _placing = false);
    }
  }

  Future<PlaceOrderResponse?> _createOrder(String draftUid) {
    return _cartRepo.placeOrder(
      draftUid,
      subOrderSchedules: _buildSubOrderSchedules(),
    );
  }

  void _handlePayOSPayment(PlaceOrderResponse checkoutData) {
    final payosUrl = checkoutData.paymentUrl;

    if (payosUrl != null && payosUrl.isNotEmpty) {
      final paymentModel = PayOSPaymentModel(
        checkoutID: checkoutData.paymentUid ?? '',
        success: true,
        checkoutUrl: payosUrl,
        amount: checkoutData.totalPrice.toInt(),
        status: 'PENDING',
        qrCode: null,
        orderCode: null,
        accountNumber: null,
        accountName: null,
        bin: null,
        description: null,
        error: null,
      );
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (context) => PayOSQrDialog(
          paymentData: paymentModel,
          onCheckStatus: () {
            Navigator.pop(context);
            Navigator.pop(context, true);
            showAppSnackBar(context, 'Payment successful!',
                type: SnackBarType.success);
          },
        ),
      );
    } else {
      showAppSnackBar(
          context, 'Failed to retrieve payment link from the server.',
          type: SnackBarType.error);
    }
  }

  void _handleCodSuccess() {
    showAppSnackBar(context, 'Order successfully placed (COD).',
        type: SnackBarType.success);
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    final o = _currentOrder;
    final double totalDiscount = _getTotalDiscount();
    final double finalTotal = math.max(0.0, o.totalAmount - totalDiscount);

    return Scaffold(
      backgroundColor: _kPeachBg,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new,
              color: _kPrimaryPink, size: 20),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('Confirm Order',
            style: TextStyle(
                color: _kDarkBrown, fontWeight: FontWeight.bold, fontSize: 18)),
        centerTitle: true,
      ),
      body: Container(
        width: double.infinity,
        decoration: const BoxDecoration(
          color: _kBgWhite,
          borderRadius: BorderRadius.only(
              topLeft: Radius.circular(30), topRight: Radius.circular(30)),
        ),
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
                children: [
                  PersonalInfoSection(
                    fullName: o.fullName,
                    phoneNumber: o.phoneNumber,
                    onEdit: _openEditPersonal,
                  ),
                  CheckoutAddressSection(
                    deliveryAddress: o.deliveryAddress,
                    onEdit: _openEditAddress,
                  ),
                  CheckoutPaymentSection(
                    paymentMethod: o.paymentMethod,
                    phoneNumber: o.phoneNumber,
                    isMomoPayment: _isMomoPayment,
                    onEdit: _openEditPayment,
                  ),
                  ChefOrderGroupsSection(
                    lines: o.lines,
                    deliveryTypes: _deliveryTypes,
                    selectedDeliveryDates: _selectedDeliveryDates,
                    selectedDeliveryTimes: _selectedDeliveryTimes,
                    fallbackDeliveryDate: o.deliveryDate,
                    fallbackDeliveryTime: o.deliveryTime,
                    isRecalculating: _isRecalculating,
                    appliedShopVouchers: _appliedShopVouchers,
                    validatingChefId: _validatingChefId,
                    onEditDeliveryTime: _openEditTimeForChef,
                    voucherControllerForChef: _voucherControllerForChef,
                    onChangeDeliveryType: _changeDeliveryType,
                    onShowVoucherList: (chefId) =>
                        _showVoucherList(context, chefId),
                    onApplyShopVoucher: _applyShopVoucher,
                    onRemoveShopVoucher: _removeShopVoucher,
                  ),
                  if (o.lines.isNotEmpty)
                    PlatformVouchersSection(
                      platformSubtotalCtrl: _platformSubtotalCtrl,
                      platformShippingCtrl: _platformShippingCtrl,
                      appliedPlatformSubtotal: _appliedPlatformSubtotal,
                      appliedPlatformShipping: _appliedPlatformShipping,
                      validatingPlatformType: _validatingPlatformType,
                      onApplyPlatformVoucher: _applyPlatformVoucher,
                      onRemovePlatformVoucher: _removePlatformVoucher,
                    ),
                  PriceSummarySection(
                    subtotal: o.subtotal,
                    taxAndFees: _taxAndFees,
                    deliveryFee: _deliveryFee,
                    totalDiscount: totalDiscount,
                    finalTotal: finalTotal,
                    placing: _placing,
                    onPlaceOrder: _placeOrder,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
