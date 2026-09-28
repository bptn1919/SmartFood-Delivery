import 'package:flutter/material.dart';
import 'package:testing/features/checkout/repositories/review_repository.dart';
import 'package:testing/features/recommend/presentations/widgets/better_alternatives_sheet.dart';
import 'package:testing/features/checkout/presentations/widgets/order_detail/order_detail_content.dart';
import 'package:testing/features/checkout/presentations/widgets/order_detail/order_detail_header.dart';
import 'package:testing/features/checkout/presentations/widgets/edit_delivery_time_sheet.dart';
import 'package:testing/features/report/presentations/create_report_bottom_sheet.dart';
import '../repositories/order_repository.dart';
import '../models/order_detail.dart';
import '../../common/app_components.dart';
import '../models/customer_order.dart';
import '../widgets/review_bottom_sheet.dart';
import 'package:intl/intl.dart';

class OrderDetailPage extends StatefulWidget {
  final String orderUid;

  const OrderDetailPage({super.key, required this.orderUid});

  @override
  State<OrderDetailPage> createState() => _OrderDetailPageState();
}

class _OrderDetailPageState extends State<OrderDetailPage> {
  // Styling colors matching the pattern
  final Color _primaryOrange = const Color(0xFFFFB68C);
  final Color _primaryRed = const Color(0xFFE55866);
  final NumberFormat _currencyFormatter = NumberFormat.currency(
    locale: 'vi_VN',
    symbol: 'đ',
    decimalDigits: 0,
  );

  final _repo = OrderRepository();
  final _reviewRepo = ReviewRepository();
  bool _loading = true;
  String? _error;
  OrderDetail? _detail;

  bool _isCancelling = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final d = await _repo.getOrderDetail(widget.orderUid);

      debugPrint(
          '✅ [OrderDetailPage] Loaded order detail: $d.items items'); // Log số lượng items để kiểm tra

      if (!mounted) return;
      setState(() {
        _detail = d;
        _loading = false;
        if (d == null) _error = "Order not found";
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _showReviewSheet(OrderLineItem item) async {
    final reviewData = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => ReviewBottomSheet(dishName: item.dishName),
    );

    if (reviewData != null) {
      final int rating = reviewData['rating'];
      final String comment = reviewData['comment'];

      if (!mounted) return;
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (c) => const Center(
            child: CircularProgressIndicator(color: Color(0xFFE55866))),
      );

      try {
        await _reviewRepo.submitReview(
          orderUid: widget.orderUid,
          dishUid: item.dishUid,
          rating: rating,
          comment: comment,
        );

        if (mounted) Navigator.pop(context);

        if (mounted) {
          showAppSnackBar(
            context,
            'Thank you for your review!',
            type: SnackBarType
                .success, // Tự động có icon check, màu xanh, bo góc và nổi lên
          );
          if (rating <= 3) {
            // Đợi 300ms để SnackBar và hiệu ứng đóng Dialog trôi qua mượt mà
            await Future.delayed(const Duration(milliseconds: 300));

            if (mounted) {
              BetterAlternativesSheet.show(
                context,
                dishUid: item.dishUid,
                dishName: item.dishName,
              );
            }
          }
        }
      } catch (e) {
        if (mounted) Navigator.pop(context);

        if (mounted) {
          showAppSnackBar(
            context,
            'Error: $e',
            type: SnackBarType.error,
          );
        }
      }
    }
  }

  Future<void> _editDeliveryTime() async {
    final current = _detail;
    if (current == null || current.status != OrderStatus.DRAFT) return;

    final result = await showModalBottomSheet<EditDeliveryTimeResult>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => EditDeliveryTimeSheet(
        initialDeliveryDate: current.deliveryDate,
        initialDeliveryTime: current.deliveryTime,
      ),
    );

    if (!mounted || result == null) return;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(
        child: CircularProgressIndicator(color: Color(0xFFE55866)),
      ),
    );

    try {
      final updated = await _repo.updateCustomerOrderDeliveryTime(
        orderUid: current.uid,
        deliveryDate: result.deliveryDate,
        deliveryTime: result.deliveryTime,
      );

      if (!mounted) return;
      Navigator.pop(context);

      setState(() {
        _detail = current.copyWith(
          deliveryDate: updated.deliveryDate,
          deliveryTime: updated.deliveryTime,
        );
      });

      showAppSnackBar(
        context,
        'Delivery time updated successfully!',
        type: SnackBarType.success,
      );
    } catch (e) {
      if (!mounted) return;
      Navigator.pop(context);
      showAppSnackBar(
        context,
        'Failed to update delivery time: $e',
        type: SnackBarType.error,
      );
    }
  }

  Future<void> _showReportSheet() async {
    final submitted = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => CreateReportBottomSheet(orderUid: widget.orderUid),
    );

    if (!mounted || submitted != true) return;
    showAppSnackBar(
      context,
      'Report submitted successfully.',
      type: SnackBarType.success,
    );
  }

  void _showCancelOrderDialog(BuildContext context, String orderUid) {
    final TextEditingController reasonController = TextEditingController();

    showDialog(
      context: context,
      barrierDismissible: false, // Bắt buộc phải bấm nút để đóng
      builder: (ctx) {
        return AlertDialog(
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Text("Cancel Order",
              style: TextStyle(
                  fontWeight: FontWeight.bold, color: Colors.redAccent)),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                  "Are you sure you want to cancel this order? If you paid via PayOS, the refund will be processed automatically."),
              const SizedBox(height: 16),
              // Ô nhập lý do hủy (Tùy chọn)
              TextField(
                controller: reasonController,
                decoration: InputDecoration(
                  hintText: "Reason for cancellation (Optional)",
                  hintStyle: const TextStyle(fontSize: 13, color: Colors.grey),
                  border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12)),
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                ),
                maxLines: 2,
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text("Keep Order",
                  style: TextStyle(
                      color: Colors.grey, fontWeight: FontWeight.bold)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.redAccent,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8)),
              ),
              onPressed: () async {
                // Đóng Dialog trước
                Navigator.pop(ctx);

                // Gọi hàm xử lý hủy đơn
                _processCancelOrder(orderUid, reasonController.text.trim());
              },
              child: const Text("Yes, Cancel",
                  style: TextStyle(color: Colors.white)),
            ),
          ],
        );
      },
    ).whenComplete(reasonController.dispose);
  }

  // Hàm gọi API thực tế
  Future<void> _processCancelOrder(String uid, String reason) async {
    setState(() => _isCancelling = true);

    final success = await _repo.cancelOrder(orderUid: uid, reason: reason);

    if (!mounted) return;
    setState(() => _isCancelling = false);

    if (success) {
      // 👇 TECH LEAD: Hiện thông báo và Load lại màn hình hoặc Back về trang trước
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text("Order cancelled successfully"),
            backgroundColor: Colors.green),
      );
      // Gọi hàm load lại data của trang này:
      // _fetchOrderDetail();

      // Hoặc văng ra trang trước:
      Navigator.pop(context, true);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text(
                "Failed to cancel order. It might have been confirmed by the chef."),
            backgroundColor: Colors.red),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _primaryOrange,
      body: Column(
        children: [
          OrderDetailHeader(
            orderUid: widget.orderUid,
            onBack: () => Navigator.pop(context),
            onReportPressed: _detail?.status == OrderStatus.COMPLETED
                ? _showReportSheet
                : null,
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
              child: _buildBody(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(
          child: CircularProgressIndicator(color: Color(0xFFE55866)));
    }

    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 48, color: Colors.red),
            const SizedBox(height: 12),
            Text(_error!, style: const TextStyle(color: Colors.grey)),
            const SizedBox(height: 16),
            OutlinedButton(
              onPressed: _load,
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    return OrderDetailContent(
      order: _detail!,
      accentColor: _primaryRed,
      currencyFormatter: _currencyFormatter,
      onRefresh: _load,
      onReviewPressed: _showReviewSheet,
      isCancelling: _isCancelling,
      onCancelPressed: (orderUid) => _showCancelOrderDialog(context, orderUid),
      onEditDeliveryTime: _editDeliveryTime,
    );
  }
}
